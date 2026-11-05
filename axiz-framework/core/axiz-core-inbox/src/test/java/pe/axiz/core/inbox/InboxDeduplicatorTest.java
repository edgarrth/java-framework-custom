package pe.axiz.core.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.Deduplicator;

/** Core decisions are tested through the port; JDBC persistence is covered in its adapter. */
class InboxDeduplicatorTest {
  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final Duration RETENTION = Duration.ofDays(7);

  static class Probe implements InboxStore {
    Acquisition answer = Acquisition.acquired();
    String consumer;
    String key;
    String owner;
    Instant received;
    Instant lease;
    Instant expiry;
    Instant processed;
    boolean complete = true;
    boolean release;

    @Override
    public Acquisition tryAcquire(
        String consumer, String key, String owner, Instant now, Instant lease, Instant expiry) {
      this.consumer = consumer;
      this.key = key;
      this.owner = owner;
      this.received = now;
      this.lease = lease;
      this.expiry = expiry;
      return answer;
    }

    @Override
    public boolean markProcessed(String consumer, String key, String owner, Instant at) {
      assertEquals(this.consumer, consumer);
      assertEquals(this.key, key);
      assertEquals(this.owner, owner);
      processed = at;
      return complete;
    }

    @Override
    public boolean release(String consumer, String key, String owner) {
      assertEquals(this.consumer, consumer);
      assertEquals(this.key, key);
      assertEquals(this.owner, owner);
      return release;
    }

    @Override
    public int purgeExpired(Instant now) {
      throw new UnsupportedOperationException();
    }
  }

  private InboxDeduplicator dedup(Probe store) {
    return new InboxDeduplicator(store, LEASE, RETENTION, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void acquisitionAndCompletionUseTheSameOwnerAndExactLeaseRetentionBoundaries() {
    var store = new Probe();
    var dedup = dedup(store);
    var claim = dedup.claim("consumer", "source-and-id");
    assertEquals(Deduplicator.Claim.Status.ACQUIRED, claim.status());
    assertNotNull(claim.token());
    assertFalse(claim.token().isBlank());
    assertEquals("consumer", store.consumer);
    assertEquals("source-and-id", store.key);
    assertEquals(claim.token(), store.owner);
    assertEquals(NOW, store.received);
    assertEquals(NOW.plus(LEASE), store.lease);
    assertEquals(NOW.plus(RETENTION), store.expiry);
    assertTrue(dedup.completed("consumer", "source-and-id", claim.token()));
    assertEquals(NOW, store.processed);
    store.complete = false;
    assertFalse(dedup.completed("consumer", "source-and-id", claim.token()));
    assertFalse(dedup.failed("consumer", "source-and-id", claim.token()));
    store.release = true;
    assertTrue(dedup.failed("consumer", "source-and-id", claim.token()));
  }

  @Test
  void processedAndBusyStatesNeverGrantExecution() {
    var store = new Probe();
    var dedup = dedup(store);
    store.answer = InboxStore.Acquisition.processed();
    assertEquals(Deduplicator.Claim.Status.ALREADY_PROCESSED, dedup.claim("c", "k").status());
    store.answer = InboxStore.Acquisition.busy(NOW.plusSeconds(7));
    var busy = dedup.claim("c", "k");
    assertEquals(Deduplicator.Claim.Status.BUSY, busy.status());
    assertEquals(Duration.ofSeconds(7), busy.retryAfter());
    store.answer = InboxStore.Acquisition.busy(null);
    assertEquals(LEASE, dedup.claim("c", "k").retryAfter());
  }

  @Test
  void invalidLeaseAndMissingStatusAreRejected() {
    var store = new Probe();
    for (var invalid : java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1)))
      assertThrows(
          IllegalArgumentException.class,
          () -> new InboxDeduplicator(store, invalid, RETENTION, Clock.systemUTC()));
    assertThrows(NullPointerException.class, () -> new InboxStore.Acquisition(null, null));
  }
}
