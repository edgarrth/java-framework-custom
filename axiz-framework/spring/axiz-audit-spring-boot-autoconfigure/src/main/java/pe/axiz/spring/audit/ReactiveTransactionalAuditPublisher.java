package pe.axiz.spring.audit;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
import pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.commons.TransactionOutcome;
import reactor.core.publisher.Mono;

/**
 * Durable audit in the same Reactor transaction as business data. Failures go to the native
 * fallback because their business transaction will roll back. No CompletionStage bridge is used.
 */
public final class ReactiveTransactionalAuditPublisher implements ReactiveAuditPublisher {
  private final Function<AuditEvent, Mono<Void>> append;
  private final ReactiveAuditPublisher fallback;
  private final AuditOutsideTransaction outside;
  private final @Nullable TransactionalOperator failures;
  private final Duration failureTimeout;
  private final Semaphore failureSlots;
  private final AtomicLong notConfirmed = new AtomicLong();

  public ReactiveTransactionalAuditPublisher(
      ReactiveOutboxWriter outbox,
      String destination,
      ReactiveAuditPublisher fallback,
      AuditOutsideTransaction outside) {
    this(outbox, destination, fallback, outside, null, Duration.ofSeconds(2), 4);
  }

  public ReactiveTransactionalAuditPublisher(
      ReactiveOutboxWriter outbox,
      String destination,
      ReactiveAuditPublisher fallback,
      AuditOutsideTransaction outside,
      @Nullable ReactiveTransactionManager transactions,
      Duration failureTimeout,
      int failureConcurrency) {
    if (!failureTimeout.isPositive() || failureConcurrency < 1)
      throw new IllegalArgumentException("Invalid durable audit failure bounds");
    this.failureTimeout = failureTimeout;
    this.failureSlots = new Semaphore(failureConcurrency);
    var definition =
        new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    definition.setTimeout((int) Math.max(1, failureTimeout.toSeconds()));
    this.failures =
        transactions == null ? null : TransactionalOperator.create(transactions, definition);
    Objects.requireNonNull(outbox);
    this.append =
        event -> outbox.write(destination, TransactionalAuditPublisher.envelope(event), "audit");
    Objects.requireNonNull(destination);
    this.fallback = Objects.requireNonNull(fallback);
    this.outside = Objects.requireNonNull(outside);
  }

  @Override
  public Mono<Void> publish(AuditEvent event) {
    return Mono.deferContextual(
        context -> {
          if (event.outcome() == AuditEvent.Outcome.FAILURE) {
            if (failures == null) return fallback.publish(event);
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
          return TransactionSynchronizationManager.forCurrentTransaction()
              .map(TransactionSynchronizationManager::isActualTransactionActive)
              .onErrorResume(NoTransactionException.class, ignored -> Mono.just(false))
              .flatMap(
                  active -> {
                    if (active) return append.apply(event);
                    if (outside == AuditOutsideTransaction.FAIL)
                      return Mono.error(
                          new AuditPublishException(
                              "Audit event "
                                  + event.id()
                                  + " recorded outside a reactive transaction",
                              new IllegalStateException("no active transaction")));
                    return fallback.publish(event);
                  });
        });
  }

  public long notConfirmedCount() {
    return notConfirmed.get();
  }

  private Mono<Void> persistFailure(AuditEvent event) {
    return Mono.defer(
        () -> {
          var transaction = failures;
          if (transaction == null) return fallback.publish(event);
          if (!failureSlots.tryAcquire())
            return degrade(
                event,
                new IllegalStateException("Audit failure persistence concurrency exhausted"));
          var registered = new AtomicBoolean();
          var released = new AtomicBoolean();
          Runnable release =
              () -> {
                if (released.compareAndSet(false, true)) failureSlots.release();
              };
          return transaction
              .execute(
                  status ->
                      TransactionSynchronizationManager.forCurrentTransaction()
                          .flatMap(
                              manager -> {
                                manager.registerSynchronization(
                                    new TransactionSynchronization() {
                                      @Override
                                      public Mono<Void> afterCompletion(int completion) {
                                        return Mono.fromRunnable(release);
                                      }
                                    });
                                registered.set(true);
                                return append.apply(event);
                              }))
              .then()
              .timeout(failureTimeout)
              .onErrorResume(failure -> degrade(event, failure))
              .doFinally(
                  signal -> {
                    if (!registered.get()) release.run();
                  });
        });
  }

  private Mono<Void> degrade(AuditEvent event, Throwable failure) {
    notConfirmed.incrementAndGet();
    LoggerFactory.getLogger(ReactiveTransactionalAuditPublisher.class)
        .error(
            "audit.persistence=NOT_CONFIRMED eventId={} action={}",
            event.id(),
            event.action(),
            failure);
    return fallback
        .publish(event.withPersistence("NOT_CONFIRMED"))
        .onErrorResume(
            rejected -> {
              LoggerFactory.getLogger(ReactiveTransactionalAuditPublisher.class)
                  .error("Audit persistence and fallback both failed for {}", event.id(), rejected);
              return Mono.empty();
            });
  }
}
