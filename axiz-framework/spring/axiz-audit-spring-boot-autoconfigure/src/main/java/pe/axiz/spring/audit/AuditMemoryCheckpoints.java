package pe.axiz.spring.audit;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.ChainCheckpoint;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.spring.foundation.BoundedAsyncDispatcher;

/** Periodic memory snapshots become evidence only after an independent strict acknowledgement. */
final class AuditMemoryCheckpoints implements SmartLifecycle, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(AuditMemoryCheckpoints.class);
  private static final IdGenerator IDS = IdGenerator.uuidV7();
  private final AuditIntegritySupport integrity;
  private final Function<AuditEvent, CompletionStage<Void>> publication;
  private final Duration interval;
  private final Duration timeout;
  private final Map<String, ChainCheckpoint> confirmed = new LinkedHashMap<>();
  private final LongAdder confirmations = new LongAdder();
  private final LongAdder failures = new LongAdder();
  private @Nullable ScheduledThreadPoolExecutor timer;
  private @Nullable ScheduledFuture<?> task;
  private @Nullable BoundedAsyncDispatcher dispatcher;
  private @Nullable CompletableFuture<Void> pending;
  private boolean running;
  private boolean cycling;
  private long generation;

  AuditMemoryCheckpoints(
      AuditIntegritySupport integrity,
      AxizAuditProperties.Strict properties,
      Function<AuditEvent, CompletionStage<Void>> publication,
      @Nullable MeterRegistry registry) {
    this.integrity = Objects.requireNonNull(integrity);
    this.publication = Objects.requireNonNull(publication);
    this.interval = properties.checkpointInterval();
    this.timeout = properties.timeout();
    if (registry != null) {
      FunctionCounter.builder("axiz.audit.checkpoint.confirmed", confirmations, LongAdder::sum)
          .register(registry);
      FunctionCounter.builder("axiz.audit.checkpoint.failed", failures, LongAdder::sum)
          .register(registry);
    }
  }

  @Override
  public boolean isAutoStartup() {
    return integrity.enabled() && integrity.hasMemoryChains();
  }

  @Override
  public synchronized void start() {
    if (running || !isAutoStartup()) return;
    // One worker plus one handoff slot permits sequential callbacks without caller-runs.
    // At most two unfinished sends can survive a timeout in a misbehaving custom strict port.
    dispatcher = new BoundedAsyncDispatcher("audit-checkpoint", 1, 1, timeout, null);
    timer =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("audit-checkpoint-tick-", 0).factory());
    timer.setRemoveOnCancelPolicy(true);
    running = true;
    task =
        timer.scheduleWithFixedDelay(
            this::tick, interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS);
  }

  /** Does not await I/O and never overlaps an unfinished cycle. */
  void tick() {
    long run;
    BoundedAsyncDispatcher deliveries;
    synchronized (this) {
      if (!running || cycling) return;
      cycling = true;
      run = ++generation;
      deliveries = Objects.requireNonNull(dispatcher);
    }
    try {
      List<ChainCheckpoint> snapshots;
      synchronized (this) {
        snapshots =
            integrity.memoryCheckpoints().stream()
                .filter(
                    head -> {
                      ChainCheckpoint previous = confirmed.get(head.chainId());
                      return previous == null || head.sequence() > previous.sequence();
                    })
                .toList();
      }
      deliver(snapshots, 0, run, deliveries);
    } catch (RuntimeException failure) {
      synchronized (this) {
        if (running && generation == run) {
          reportFailure(failure);
          cycling = false;
        }
      }
    }
  }

  private void deliver(
      List<ChainCheckpoint> snapshots, int index, long run, BoundedAsyncDispatcher deliveries) {
    synchronized (this) {
      if (!running || generation != run) return;
      if (index == snapshots.size()) {
        cycling = false;
        return;
      }
    }
    ChainCheckpoint snapshot = snapshots.get(index);
    var confirmation =
        deliveries.submit(() -> publication.apply(event(snapshot))).toCompletableFuture();
    synchronized (this) {
      if (!running || generation != run) {
        confirmation.cancel(true);
        return;
      }
      pending = confirmation;
    }
    Stages.observe(
        confirmation,
        (ignored, failure) -> {
          synchronized (this) {
            if (!running || generation != run) return;
            pending = null;
            if (failure == null) {
              confirmed.put(snapshot.chainId(), snapshot);
              confirmations.increment();
            } else reportFailure(failure);
          }
          deliver(snapshots, index + 1, run, deliveries);
        },
        failure -> {
          synchronized (this) {
            if (!running || generation != run) return;
            pending = null;
            cycling = false;
            reportFailure(failure);
          }
        });
  }

  private void reportFailure(Throwable failure) {
    failures.increment();
    LOG.warn("Audit checkpoint not confirmed: failureType={}", failure.getClass().getName());
  }

  private static AuditEvent event(ChainCheckpoint checkpoint) {
    return AuditEvent.builder()
        .id(IDS.newId())
        .occurredAt(checkpoint.at())
        .service("axiz.audit")
        .action("audit.chain.checkpoint")
        .resourceType("audit-chain")
        .resourceId(checkpoint.chainId())
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel("internal")
        .tenant(null)
        .details(
            Map.of(
                "chainId", checkpoint.chainId(),
                "sequence", checkpoint.sequence(),
                "hash", checkpoint.hash(),
                "at", checkpoint.at().toString()))
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  /** Diagnostic snapshot; the external verifier obtains its checkpoint from the durable sink. */
  synchronized Map<String, ChainCheckpoint> confirmedSnapshots() {
    return Map.copyOf(confirmed);
  }

  @Override
  public synchronized boolean isRunning() {
    return running;
  }

  @Override
  public void stop() {
    ScheduledThreadPoolExecutor ownedTimer;
    BoundedAsyncDispatcher ownedDispatcher;
    CompletableFuture<Void> ownedPending;
    synchronized (this) {
      running = false;
      cycling = false;
      generation++;
      ownedTimer = timer;
      ownedDispatcher = dispatcher;
      ownedPending = pending;
      if (task != null) task.cancel(false);
      task = null;
      timer = null;
      dispatcher = null;
      pending = null;
    }
    if (ownedTimer != null) ownedTimer.shutdownNow();
    if (ownedPending != null) ownedPending.cancel(true);
    if (ownedDispatcher != null) ownedDispatcher.close();
  }

  @Override
  public void close() {
    stop();
  }
}
