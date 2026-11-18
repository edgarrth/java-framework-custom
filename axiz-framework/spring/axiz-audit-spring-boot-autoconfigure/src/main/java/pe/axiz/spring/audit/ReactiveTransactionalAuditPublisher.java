package pe.axiz.spring.audit;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditIntegrityMetrics;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditSealCalculator;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import reactor.core.publisher.Mono;

/**
 * Durable audit in the same Reactor transaction as business data. Failures go to the native
 * fallback because their business transaction will roll back. No CompletionStage bridge is used.
 */
public final class ReactiveTransactionalAuditPublisher
    implements ReactiveAuditPublisher, AuditIntegrityMetrics {
  private final Function<AuditEvent, Mono<Void>> append;
  private final ReactiveAuditPublisher fallback;
  private final AuditOutsideTransaction outside;
  private final TransactionalOperator failures;
  private final Function<AuditEvent, Mono<AuditEvent>> seal;
  private final AuditSealer degradation;
  private final AtomicLong sequence = new AtomicLong();
  private final Duration failureTimeout;
  private final Semaphore failureSlots;
  private final LongAdder sanitized = new LongAdder();
  private final AtomicLong notConfirmed = new AtomicLong();

  private ReactiveTransactionalAuditPublisher(Builder builder) {
    ReactiveOutboxWriter outbox = Objects.requireNonNull(builder.outbox, "outbox");
    String destination = Objects.requireNonNull(builder.destination, "destination");
    ReactiveAuditPublisher fallback = Objects.requireNonNull(builder.fallback, "fallback");
    AuditOutsideTransaction outside = Objects.requireNonNull(builder.outside, "outside");
    ReactiveTransactionManager transactions =
        Objects.requireNonNull(builder.transactions, "transactions");
    Duration failureTimeout = Objects.requireNonNull(builder.failureTimeout, "failureTimeout");
    int failureConcurrency = builder.failureConcurrency;
    ReactiveAuditChainState state = Objects.requireNonNull(builder.state, "state");
    String chain = Objects.requireNonNull(builder.chain, "chain");
    AuditSealCalculator calculator = Objects.requireNonNull(builder.calculator, "calculator");
    AuditSealer degradation = Objects.requireNonNull(builder.degradation, "degradation");
    if (!failureTimeout.isPositive()
        || failureTimeout.compareTo(Duration.ofMinutes(1)) > 0
        || failureConcurrency < 1)
      throw new IllegalArgumentException("Invalid durable audit failure bounds");
    this.failureTimeout = failureTimeout;
    this.failureSlots = new Semaphore(failureConcurrency);
    var definition =
        new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    definition.setTimeout((int) ((failureTimeout.toNanos() + 999_999_999L) / 1_000_000_000L));
    this.failures = TransactionalOperator.create(Objects.requireNonNull(transactions), definition);

    if (state.transactionResource() == null
        || state.transactionResource() != outbox.transactionResource())
      throw new IllegalArgumentException(
          "Durable audit head and outbox must use the same transaction resource");
    this.seal =
        event ->
            calculator.enabled()
                ? state
                    .advance(chain, head -> calculator.next(event, head, null))
                    .map(
                        position -> {
                          sequence.accumulateAndGet(position.sequence(), Math::max);
                          return event.withIntegrity(position);
                        })
                : Mono.just(event);
    this.degradation = degradation;
    this.append =
        event -> outbox.write(destination, TransactionalAuditPublisher.envelope(event), "audit");

    this.fallback = fallback;
    this.outside = outside;
  }

  @Override
  public Mono<Void> publish(AuditEvent event) {
    return Mono.deferContextual(
        context -> {
          if (event.integrity() != null)
            return Mono.error(new IllegalArgumentException("Expected an unsealed fact"));
          sanitized.add(event.detailsSanitized());
          if (event.outcome() == AuditEvent.Outcome.FAILURE) {
            return TransactionSynchronizationManager.forCurrentTransaction()
                .map(TransactionSynchronizationManager::isActualTransactionActive)
                .onErrorResume(NoTransactionException.class, ignored -> Mono.just(false))
                .flatMap(
                    active -> {
                      if (!active) return persistFailure(event);
                      return ReactiveTransactionOutcome.capture()
                          .flatMap(
                              trace -> {
                                Supplier<Mono<Void>> persist =
                                    () ->
                                        persistFailure(
                                            event.withOutcomes(
                                                event.transactionOutcome()
                                                        == TransactionOutcome.UNKNOWN
                                                    ? trace.local(null)
                                                    : event.transactionOutcome(),
                                                event.operationOutcome()));
                                var pending = ReactivePendingAuditFailures.current(context);
                                if (pending != null && pending.add(persist)) return Mono.empty();
                                return persist.get();
                              });
                    });
          }
          return publishSuccess(event);
        });
  }

  private Mono<Void> publishSuccess(AuditEvent event) {
    return TransactionSynchronizationManager.forCurrentTransaction()
        .flatMap(
            manager -> {
              if (!manager.isActualTransactionActive()) return outside(event);
              if (!manager.isSynchronizationActive())
                return Mono.error(
                    new IllegalStateException(
                        "Durable audit requires transaction synchronization"));
              manager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public Mono<Void> beforeCommit(boolean readOnly) {
                      if (readOnly)
                        return Mono.error(
                            new IllegalStateException(
                                "Durable audit requires a writable transaction"));
                      return seal.apply(
                              event.withOutcomes(
                                  TransactionOutcome.COMMITTED, event.operationOutcome()))
                          .flatMap(append);
                    }
                  });
              return Mono.<Void>empty();
            })
        .onErrorResume(NoTransactionException.class, ignored -> outside(event));
  }

  private Mono<Void> outside(AuditEvent event) {
    if (outside == AuditOutsideTransaction.FAIL)
      return Mono.error(
          new AuditPublishException(
              "Audit success recorded outside a reactive transaction",
              new IllegalStateException("no active transaction")));
    return fallback.publish(degradation.seal(event));
  }

  @Override
  public long detailsSanitizedCount() {
    return sanitized.sum();
  }

  @Override
  public Map<String, Long> chainSequences() {
    return Map.of("durable", sequence.get(), "degradation", degradation.sequence());
  }

  public long notConfirmedCount() {
    return notConfirmed.get();
  }

  private Mono<Void> persistFailure(AuditEvent event) {
    return Mono.defer(
        () -> {
          var attempt = new DurableAuditAttempt(event);
          if (!failureSlots.tryAcquire())
            return degrade(
                attempt,
                new IllegalStateException("Audit failure persistence concurrency exhausted"));
          var registered = new AtomicBoolean();
          var released = new AtomicBoolean();
          Runnable release =
              () -> {
                if (released.compareAndSet(false, true)) failureSlots.release();
              };
          return failures
              .execute(
                  status ->
                      TransactionSynchronizationManager.forCurrentTransaction()
                          .flatMap(
                              manager -> {
                                manager.registerSynchronization(
                                    new TransactionSynchronization() {
                                      @Override
                                      public Mono<Void> beforeCommit(boolean readOnly) {
                                        return Mono.fromRunnable(attempt::requireRunning);
                                      }

                                      @Override
                                      public Mono<Void> afterCompletion(int completion) {
                                        return Mono.fromRunnable(
                                            () -> {
                                              attempt.complete(
                                                  completion == STATUS_COMMITTED
                                                      ? TransactionOutcome.COMMITTED
                                                      : completion == STATUS_ROLLED_BACK
                                                          ? TransactionOutcome.ROLLED_BACK
                                                          : TransactionOutcome.UNKNOWN);
                                              release.run();
                                            });
                                      }
                                    });
                                registered.set(true);
                                attempt.requireRunning();
                                return seal.apply(event)
                                    .map(attempt::remember)
                                    .flatMap(
                                        signed -> {
                                          attempt.requireRunning();
                                          return append
                                              .apply(signed)
                                              .then(Mono.fromRunnable(attempt::requireRunning));
                                        });
                              }))
              .then()
              .doOnCancel(attempt::cancel)
              .timeout(failureTimeout)
              .onErrorResume(failure -> degrade(attempt, failure))
              .doFinally(
                  signal -> {
                    if (!registered.get()) release.run();
                  });
        });
  }

  private Mono<Void> degrade(DurableAuditAttempt attempt, Throwable failure) {
    return Mono.defer(
        () -> {
          if (attempt.committed()) return Mono.empty();
          notConfirmed.incrementAndGet();
          LoggerFactory.getLogger(ReactiveTransactionalAuditPublisher.class)
              .error("Durable audit not confirmed; failureType={}", failure.getClass().getName());
          return Mono.defer(() -> fallback.publish(attempt.fallback(degradation)))
              .onErrorResume(
                  rejected -> {
                    LoggerFactory.getLogger(ReactiveTransactionalAuditPublisher.class)
                        .error(
                            "Audit persistence and fallback failed; failureType={}",
                            rejected.getClass().getName());
                    return Mono.empty();
                  });
        });
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable ReactiveOutboxWriter outbox = null;
    private @Nullable String destination = null;
    private @Nullable ReactiveAuditPublisher fallback = null;
    private @Nullable AuditOutsideTransaction outside = null;
    private @Nullable ReactiveTransactionManager transactions = null;
    private @Nullable Duration failureTimeout = null;
    private int failureConcurrency = 0;
    private @Nullable ReactiveAuditChainState state = null;
    private @Nullable String chain = null;
    private @Nullable AuditSealCalculator calculator = null;
    private @Nullable AuditSealer degradation = null;

    private Builder() {}

    public Builder outbox(ReactiveOutboxWriter value) {
      this.outbox = value;
      return this;
    }

    public Builder destination(String value) {
      this.destination = value;
      return this;
    }

    public Builder fallback(ReactiveAuditPublisher value) {
      this.fallback = value;
      return this;
    }

    public Builder outside(AuditOutsideTransaction value) {
      this.outside = value;
      return this;
    }

    public Builder transactions(ReactiveTransactionManager value) {
      this.transactions = value;
      return this;
    }

    public Builder failureTimeout(Duration value) {
      this.failureTimeout = value;
      return this;
    }

    public Builder failureConcurrency(int value) {
      this.failureConcurrency = value;
      return this;
    }

    public Builder state(ReactiveAuditChainState value) {
      this.state = value;
      return this;
    }

    public Builder chain(String value) {
      this.chain = value;
      return this;
    }

    public Builder calculator(AuditSealCalculator value) {
      this.calculator = value;
      return this;
    }

    public Builder degradation(AuditSealer value) {
      this.degradation = value;
      return this;
    }

    public ReactiveTransactionalAuditPublisher build() {
      return new ReactiveTransactionalAuditPublisher(this);
    }
  }
}
