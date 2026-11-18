package pe.axiz.spring.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.audit.AsyncAuditPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditIntegrityMetrics;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditPublisherDecorator;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.outbox.OutboxWriter;

/**
 * Durable audit: a successful action recorded inside a transaction is written to the outbox in that
 * same transaction, so the evidence exists if and only if the business change was committed, and
 * survives a crash right after the commit (the relay publishes it later, at least once, with the
 * event id for deduplication). The event keeps the CloudEvents attributes of the other audit
 * transports, so consumers see the same format.
 *
 * <p>Events that cannot be tied to a commit go to the fallback (the asynchronous publisher): those
 * recorded outside a transaction, unless {@code outside-transaction=fail}, and failures, whose
 * transaction is about to roll back and would take the audit record with it.
 */
public final class TransactionalAuditPublisher
    implements AuditPublisher, AuditPublisherDecorator, AutoCloseable, AuditIntegrityMetrics {

  private final OutboxWriter outbox;

  private final String destination;
  private final AuditPublisher fallback;
  private final AuditOutsideTransaction outside;
  private final TransactionTemplate failures;
  private final AuditSealer durable;
  private final AuditSealer degradation;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Semaphore failureSlots;
  private final Duration failureTimeout;
  private final Set<Thread> failureWorkers = ConcurrentHashMap.newKeySet();
  private final LongAdder sanitized = new LongAdder();
  private final AtomicLong notConfirmed = new AtomicLong();

  private TransactionalAuditPublisher(Builder builder) {
    OutboxWriter outbox = Objects.requireNonNull(builder.outbox, "outbox");
    String destination = Objects.requireNonNull(builder.destination, "destination");
    AuditPublisher fallback = Objects.requireNonNull(builder.fallback, "fallback");
    AuditOutsideTransaction outside = Objects.requireNonNull(builder.outside, "outside");
    PlatformTransactionManager transactions =
        Objects.requireNonNull(builder.transactions, "transactions");
    Duration timeout = Objects.requireNonNull(builder.timeout, "timeout");
    int concurrency = builder.concurrency;
    AuditSealer durable = Objects.requireNonNull(builder.durable, "durable");
    AuditSealer degradation = Objects.requireNonNull(builder.degradation, "degradation");
    if (!timeout.isPositive() || timeout.compareTo(Duration.ofMinutes(1)) > 0 || concurrency < 1)
      throw new IllegalArgumentException("Invalid audit failure timeout or concurrency");
    this.failureSlots = new Semaphore(concurrency);
    this.failureTimeout = timeout;
    this.failures = new TransactionTemplate(Objects.requireNonNull(transactions));
    this.failures.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.failures.setTimeout((int) ((timeout.toNanos() + 999_999_999L) / 1_000_000_000L));
    this.outbox = outbox;
    this.destination = destination;
    this.fallback = fallback;
    this.outside = outside;
    this.durable = durable;
    this.degradation = degradation;
    if (durable.transactionResource() == null
        || durable.transactionResource() != outbox.transactionResource())
      throw new IllegalArgumentException(
          "Durable audit head and outbox must use the same transaction resource");
  }

  @Override
  public void publish(AuditEvent event) {
    if (closed.get()) throw new IllegalStateException("Audit publisher is closed");
    if (event.integrity() != null) throw new IllegalArgumentException("Expected an unsealed fact");
    sanitized.add(event.detailsSanitized());
    if (event.outcome() == AuditEvent.Outcome.FAILURE) {
      var pending = PendingAuditFailures.current();
      var outcome = JdbcTransactionOutcome.capture();
      Runnable persist =
          () ->
              persistFailure(
                  event.withOutcomes(
                      event.transactionOutcome() == TransactionOutcome.UNKNOWN
                          ? outcome.local(null)
                          : event.transactionOutcome(),
                      event.operationOutcome()));
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || pending == null
          || !pending.add(persist)) persist.run();
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      if (!TransactionSynchronizationManager.isSynchronizationActive())
        throw new IllegalStateException("Durable audit requires transaction synchronization");
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
              if (readOnly)
                throw new IllegalStateException("Durable audit requires a writable transaction");
              AuditEvent completed =
                  event.withOutcomes(TransactionOutcome.COMMITTED, event.operationOutcome());
              outbox.write(destination, envelope(durable.seal(completed)), "audit");
            }
          });
      return;
    }
    if (outside == AuditOutsideTransaction.FAIL) {
      throw new AuditPublishException(
          "Audit success recorded outside a transaction"
              + " (axiz.audit.durable.outside-transaction=fail)",
          new IllegalStateException("no active transaction"));
    }
    fallback.publish(degradation.seal(event));
  }

  @Override
  public long detailsSanitizedCount() {
    return sanitized.sum();
  }

  @Override
  public Map<String, Long> chainSequences() {
    return Map.of("durable", durable.sequence(), "degradation", degradation.sequence());
  }

  public long notConfirmedCount() {
    return notConfirmed.get();
  }

  private void persistFailure(AuditEvent event) {
    var attempt = new DurableAuditAttempt(event);
    if (!failureSlots.tryAcquire()) {
      degrade(
          attempt, new IllegalStateException("Audit failure persistence concurrency exhausted"));
      return;
    }
    var task =
        new FutureTask<Void>(
            () -> {
              failures.executeWithoutResult(
                  status -> {
                    TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                          @Override
                          public void beforeCommit(boolean readOnly) {
                            attempt.requireRunning();
                          }

                          @Override
                          public void afterCompletion(int completion) {
                            attempt.complete(
                                completion == STATUS_COMMITTED
                                    ? TransactionOutcome.COMMITTED
                                    : completion == STATUS_ROLLED_BACK
                                        ? TransactionOutcome.ROLLED_BACK
                                        : TransactionOutcome.UNKNOWN);
                          }
                        });
                    attempt.requireRunning();
                    AuditEvent signed = attempt.remember(durable.seal(event));
                    attempt.requireRunning();
                    outbox.write(destination, envelope(signed), "audit");
                    attempt.requireRunning();
                  });
              return null;
            });
    Thread worker =
        Thread.ofVirtual()
            .name("axiz-audit-failure")
            .unstarted(
                () -> {
                  try {
                    task.run();
                  } finally {
                    failureSlots.release();
                    failureWorkers.remove(Thread.currentThread());
                  }
                });
    failureWorkers.add(worker);
    try {
      worker.start();
    } catch (RuntimeException startFailure) {
      failureWorkers.remove(worker);
      failureSlots.release();
      degrade(attempt, startFailure);
      return;
    }
    try {
      task.get(failureTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      attempt.cancel();
      task.cancel(true);
      Thread.currentThread().interrupt();
      degrade(attempt, interrupted);
    } catch (TimeoutException timeout) {
      attempt.cancel();
      task.cancel(true);
      degrade(attempt, timeout);
    } catch (ExecutionException failure) {
      degrade(attempt, failure);
    }
  }

  private void degrade(DurableAuditAttempt attempt, Throwable failure) {
    if (attempt.committed()) return;
    notConfirmed.incrementAndGet();
    LoggerFactory.getLogger(TransactionalAuditPublisher.class)
        .error("Durable audit not confirmed; failureType={}", failure.getClass().getName());
    try {
      AuditEvent next = attempt.fallback(degradation);
      if (fallback instanceof AsyncAuditPublisher queue) queue.publishWithoutWait(next);
      else fallback.publish(next);
    } catch (RuntimeException rejected) {
      LoggerFactory.getLogger(TransactionalAuditPublisher.class)
          .error(
              "Audit persistence and fallback failed; failureType={}",
              rejected.getClass().getName());
    }
  }

  @Override
  public AuditPublisher delegate() {
    return fallback;
  }

  static EventEnvelope envelope(AuditEvent event) {
    Map<String, String> ce = event.cloudEventAttributes();
    Map<String, String> extensions = new LinkedHashMap<>();
    if (event.correlationId() != null) {
      extensions.put(EventEnvelope.CORRELATION_ID, event.correlationId());
    }
    var integrity = event.integrity();
    if (integrity != null) {
      extensions.put("axizchain", integrity.chainId());
      extensions.put("axizseq", Long.toString(integrity.sequence()));
      extensions.put("axizhash", integrity.hash());
    }
    return EventEnvelope.builder()
        .id(event.deliveryId())
        .source(Objects.requireNonNull(ce.get("source")))
        .type(AuditEvent.CLOUD_EVENT_TYPE)
        .time(event.occurredAt())
        .subject(ce.get("subject"))
        .dataContentType("application/json")
        .data(JsonWriter.writeCanonical(event.toMap()).getBytes(StandardCharsets.UTF_8))
        .extensions(extensions)
        .build();
  }

  @Override
  public void close() throws Exception {
    if (!closed.compareAndSet(false, true)) return;
    for (Thread worker : failureWorkers) worker.interrupt();
    for (Thread worker : failureWorkers) {
      try {
        worker.join(failureTimeout);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (fallback instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable OutboxWriter outbox = null;
    private @Nullable String destination = null;
    private @Nullable AuditPublisher fallback = null;
    private @Nullable AuditOutsideTransaction outside = null;
    private @Nullable PlatformTransactionManager transactions = null;
    private @Nullable Duration timeout = null;
    private int concurrency = 0;
    private @Nullable AuditSealer durable = null;
    private @Nullable AuditSealer degradation = null;

    private Builder() {}

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Borrowed outbox port must share the transaction resource and lifecycle.")
    public Builder outbox(OutboxWriter value) {
      this.outbox = value;
      return this;
    }

    public Builder destination(String value) {
      this.destination = value;
      return this;
    }

    public Builder fallback(AuditPublisher value) {
      this.fallback = value;
      return this;
    }

    public Builder outside(AuditOutsideTransaction value) {
      this.outside = value;
      return this;
    }

    public Builder transactions(PlatformTransactionManager value) {
      this.transactions = value;
      return this;
    }

    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    public Builder concurrency(int value) {
      this.concurrency = value;
      return this;
    }

    public Builder durable(AuditSealer value) {
      this.durable = value;
      return this;
    }

    public Builder degradation(AuditSealer value) {
      this.degradation = value;
      return this;
    }

    public TransactionalAuditPublisher build() {
      return new TransactionalAuditPublisher(this);
    }
  }
}
