package pe.axiz.spring.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.audit.AsyncAuditPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.commons.json.JsonWriter;
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
public final class TransactionalAuditPublisher implements AuditPublisher, AutoCloseable {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "OutboxWriter is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final OutboxWriter outbox;

  private final String destination;
  private final AuditPublisher fallback;
  private final AuditOutsideTransaction outside;
  private final @Nullable TransactionTemplate failures;
  private final Semaphore failureSlots;
  private final Duration failureTimeout;
  private final Set<Thread> failureWorkers = ConcurrentHashMap.newKeySet();
  private final AtomicLong notConfirmed = new AtomicLong();

  public TransactionalAuditPublisher(
      OutboxWriter outbox,
      String destination,
      AuditPublisher fallback,
      AuditOutsideTransaction outside) {
    this(outbox, destination, fallback, outside, null, Duration.ofSeconds(2), 4);
  }

  public TransactionalAuditPublisher(
      OutboxWriter outbox,
      String destination,
      AuditPublisher fallback,
      AuditOutsideTransaction outside,
      @Nullable PlatformTransactionManager transactions,
      Duration timeout,
      int concurrency) {
    if (!timeout.isPositive() || concurrency < 1)
      throw new IllegalArgumentException("Audit failure timeout and concurrency must be positive");
    this.failureSlots = new Semaphore(concurrency);
    this.failureTimeout = timeout;
    this.failures = transactions == null ? null : new TransactionTemplate(transactions);
    if (this.failures != null) {
      this.failures.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      this.failures.setTimeout((int) Math.max(1, timeout.toSeconds()));
    }
    this.outbox = Objects.requireNonNull(outbox);
    this.destination = Objects.requireNonNull(destination);
    this.fallback = Objects.requireNonNull(fallback);
    this.outside = Objects.requireNonNull(outside);
  }

  @Override
  public void publish(AuditEvent event) {
    if (event.outcome() == AuditEvent.Outcome.FAILURE) {
      if (failures == null) {
        fallback.publish(event);
        return;
      }
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
      outbox.write(destination, envelope(event), "audit");
      return;
    }
    if (outside == AuditOutsideTransaction.FAIL) {
      throw new AuditPublishException(
          "Audit event "
              + event.id()
              + " ("
              + event.action()
              + ") recorded outside a transaction (axiz.audit.durable.outside-transaction=fail)",
          new IllegalStateException("no active transaction"));
    }
    fallback.publish(event);
  }

  public long notConfirmedCount() {
    return notConfirmed.get();
  }

  private void persistFailure(AuditEvent event) {
    var template = failures;
    if (template == null) {
      fallback.publish(event);
      return;
    }
    if (!failureSlots.tryAcquire()) {
      degrade(event, new IllegalStateException("Audit failure persistence concurrency exhausted"));
      return;
    }
    var cancelled = new AtomicBoolean();
    var task =
        new FutureTask<Void>(
            () -> {
              template.executeWithoutResult(
                  status -> {
                    if (cancelled.get() || Thread.currentThread().isInterrupted())
                      throw new CancellationException("Audit deadline elapsed");
                    outbox.write(destination, envelope(event), "audit");
                    if (cancelled.get()) throw new CancellationException("Audit deadline elapsed");
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
      degrade(event, startFailure);
      return;
    }
    try {
      task.get(failureTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      cancelled.set(true);
      task.cancel(true);
      Thread.currentThread().interrupt();
      degrade(
          event, new IllegalStateException("Audit failure persistence interrupted", interrupted));
    } catch (TimeoutException timeout) {
      cancelled.set(true);
      task.cancel(true);
      degrade(
          event, new IllegalStateException("Audit failure persistence deadline elapsed", timeout));
    } catch (ExecutionException failure) {
      degrade(
          event,
          new IllegalStateException("Audit failure persistence rejected", failure.getCause()));
    }
  }

  private void degrade(AuditEvent event, RuntimeException failure) {
    notConfirmed.incrementAndGet();
    LoggerFactory.getLogger(TransactionalAuditPublisher.class)
        .error(
            "audit.persistence=NOT_CONFIRMED eventId={} action={}",
            event.id(),
            event.action(),
            failure);
    try {
      if (fallback instanceof AsyncAuditPublisher queue)
        queue.publishWithoutWait(event.withPersistence("NOT_CONFIRMED"));
      else fallback.publish(event.withPersistence("NOT_CONFIRMED"));
    } catch (RuntimeException rejected) {
      LoggerFactory.getLogger(TransactionalAuditPublisher.class)
          .error("Audit persistence and fallback both failed for {}", event.id(), rejected);
    }
  }

  public AuditPublisher fallback() {
    return fallback;
  }

  static EventEnvelope envelope(AuditEvent event) {
    Map<String, String> ce = event.cloudEventAttributes();
    Map<String, String> extensions = new LinkedHashMap<>();
    if (event.correlationId() != null) {
      extensions.put(EventEnvelope.CORRELATION_ID, event.correlationId());
    }
    return new EventEnvelope(
        event.id(),
        Objects.requireNonNull(ce.get("source")),
        AuditEvent.CLOUD_EVENT_TYPE,
        event.occurredAt(),
        ce.get("subject"),
        "application/json",
        JsonWriter.write(event.toMap()).getBytes(StandardCharsets.UTF_8),
        extensions);
  }

  @Override
  public void close() throws Exception {
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
}
