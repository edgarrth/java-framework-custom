package pe.axiz.core.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.jdbc.TransactionRunner;
import pe.axiz.core.messaging.CloudEventJson;
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

  public OutboxRelay(
      OutboxStore store,
      EventPublisher publisher,
      TransactionRunner transactions,
      Clock clock,
      int batchSize,
      int maxAttempts,
      Backoff backoff) {
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
  }

  /** Result of one relay run. */
  public record RunResult(int published, int retried, int failed) {
    public int total() {
      return published + retried + failed;
    }
  }

  public RunResult relayOnce() {
    try {
      return transactions.inTransaction(this::relayBatch);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new OutboxException("Outbox relay failed", e);
    }
  }

  private RunResult relayBatch() {
    Instant now = clock.instant();
    List<OutboxRecord> batch = store.lockDue(batchSize, now);
    int published = 0;
    int retried = 0;
    int failed = 0;
    for (OutboxRecord r : batch) {
      try {
        publisher.publish(r.destination(), CloudEventJson.read(r.envelopeJson()));
        store.markPublished(r.id(), clock.instant());
        published++;
      } catch (RuntimeException e) {
        int attempts = r.attempts() + 1;
        boolean giveUp = attempts >= maxAttempts;
        Duration delay = backoff.delay(attempts);
        store.markFailed(r.id(), attempts, now.plus(delay), e.toString(), giveUp);
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
    } while (r.total() == batchSize);
    return total;
  }
}
