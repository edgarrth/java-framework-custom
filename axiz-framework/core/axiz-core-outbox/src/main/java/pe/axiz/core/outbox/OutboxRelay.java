package pe.axiz.core.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.resilience.Backoff;

/**
 * Moves outbox entries to the broker (at-least-once). Each run locks a batch, publishes in order
 * and marks every entry as published or schedules a retry with backoff; after {@code maxAttempts}
 * the entry is marked FAILED and reported. Concurrent relays on PostgreSQL skip locked rows.
 */
public final class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxStore store;
  private final EventPublisher publisher;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final int batchSize;
  private final int maxAttempts;
  private final Backoff backoff;
  private final Duration lease;

  public OutboxRelay(
      OutboxStore store,
      EventPublisher publisher,
      TransactionRunner transactions,
      Clock clock,
      int batchSize,
      int maxAttempts,
      Backoff backoff) {
    this(
        store,
        publisher,
        transactions,
        clock,
        batchSize,
        maxAttempts,
        backoff,
        Duration.ofMinutes(3));
  }

  public OutboxRelay(
      OutboxStore store,
      EventPublisher publisher,
      TransactionRunner transactions,
      Clock clock,
      int batchSize,
      int maxAttempts,
      Backoff backoff,
      Duration lease) {
    this.store = Objects.requireNonNull(store);
    this.publisher = Objects.requireNonNull(publisher);
    this.transactions = Objects.requireNonNull(transactions);
    this.clock = Objects.requireNonNull(clock);
    if (batchSize < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("batchSize and maxAttempts must be positive");
    }
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.backoff = Objects.requireNonNull(backoff);
    this.lease = Objects.requireNonNull(lease);
    if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive");
  }

  /** Result of one relay run. */
  public record RunResult(int published, int retried, int failed) {
    public int total() {
      return published + retried + failed;
    }
  }

  private <T> T transaction(Callable<T> work) {
    try {
      return transactions.inTransaction(work);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new OutboxException("Outbox transaction failed", failure);
    }
  }

  public RunResult relayOnce() {
    int published = 0;
    int retried = 0;
    int failed = 0;
    RunResult result;
    do {
      result = relayClaim(batchSize - published - retried - failed);
      published += result.published();
      retried += result.retried();
      failed += result.failed();
    } while (published + retried + failed < batchSize && result.published() > 0);
    return new RunResult(published, retried, failed);
  }

  private RunResult relayClaim(int limit) {
    Instant now = clock.instant();
    String owner = UUID.randomUUID().toString();
    List<OutboxRecord> batch = transaction(() -> store.claimDue(limit, now, lease, owner));
    int published = 0;
    int retried = 0;
    int failed = 0;
    for (OutboxRecord r : batch) {
      if (r.leaseUntil() == null || !r.leaseUntil().isAfter(clock.instant())) continue;
      try {
        publisher.publish(r.destination(), r.event());
        if (transaction(() -> store.markPublished(r.id(), owner, clock.instant()))) published++;
      } catch (RuntimeException e) {
        int attempts = r.attempts() + 1;
        boolean giveUp = attempts >= maxAttempts;
        Duration delay = backoff.delay(attempts);
        if (!transaction(
            () ->
                store.markFailed(
                    r.id(), owner, attempts, clock.instant().plus(delay), e.toString(), giveUp)))
          continue;
        if (giveUp) {
          failed++;
          LOG.error(
              "Outbox entry {} ({}) failed permanently after {} attempts",
              r.id(),
              r.eventType(),
              attempts,
              e);
        } else {
          retried++;
          LOG.warn(
              "Outbox entry {} ({}) failed, retry {} in {}",
              r.id(),
              r.eventType(),
              attempts,
              delay);
        }
      }
    }
    return new RunResult(published, retried, failed);
  }

  /** Runs until a batch comes back smaller than the batch size (drains the backlog). */
  public int drain() {
    int total = 0;
    RunResult r;
    do {
      r = relayOnce();
      total += r.published();
    } while (r.published() > 0);
    return total;
  }
}
