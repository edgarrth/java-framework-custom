package pe.axiz.adapter.inbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.test.support.r2dbc.FakeR2dbc;

class R2dbcInboxStoreTest {

  private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static R2dbcInboxStore store(FakeR2dbc db) {
    return new R2dbcInboxStore(new R2dbcSql(db), "axiz_inbox", TIMEOUT);
  }

  private static Acquisition acquire(R2dbcInboxStore store, String owner) {
    return store.tryAcquire("c", "m-1", owner, NOW, NOW.plusSeconds(60), NOW.plusSeconds(3600));
  }

  @Test
  void firstDeliveryAcquiresTheMessage() {
    FakeR2dbc db = new FakeR2dbc().onUpdate("^INSERT INTO axiz_inbox", b -> 1L);
    assertEquals(Acquisition.Status.ACQUIRED, acquire(store(db), "owner-a").status());
    var insert = db.last("ON CONFLICT (consumer, message_id) DO NOTHING");
    assertEquals("c", insert.binds().get(0));
    assertEquals("m-1", insert.binds().get(1));
    assertEquals("owner-a", insert.binds().get(2));
    assertEquals(1, db.executions().size(), "no takeover attempt");
  }

  @Test
  void processedBusyAndExpiredLeasesAreToldApart() {
    AtomicLong takeover = new AtomicLong(0);
    AtomicReference<String> status = new AtomicReference<>("IN_PROGRESS");
    Instant leaseEnd = NOW.plusSeconds(40);
    FakeR2dbc db =
        new FakeR2dbc()
            .onUpdate("^INSERT INTO axiz_inbox", b -> 0L)
            .onUpdate("^UPDATE axiz_inbox SET owner", b -> takeover.get())
            .onQuery(
                "^SELECT status, lease_until",
                b -> List.of(Map.of("status", status.get(), "lease_until", leaseEnd)));
    R2dbcInboxStore store = store(db);

    Acquisition busy = acquire(store, "owner-b");
    assertEquals(Acquisition.Status.BUSY, busy.status());
    assertEquals(leaseEnd, busy.leaseUntil());
    status.set("PROCESSED");
    assertEquals(Acquisition.Status.PROCESSED, acquire(store, "owner-b").status());
    takeover.set(1);
    assertEquals(Acquisition.Status.ACQUIRED, acquire(store, "owner-b").status());
    var update = db.last("SET owner");
    assertTrue(update.sql().contains("lease_until < $3"), update.sql());
    assertEquals("owner-b", update.binds().get(0));
  }

  @Test
  void completionAndReleaseRequireTheOwner() {
    FakeR2dbc db =
        new FakeR2dbc()
            .onUpdate("SET status = 'PROCESSED'", b -> "owner-b".equals(b.get(3)) ? 1L : 0L)
            .onUpdate(
                "^DELETE FROM axiz_inbox WHERE consumer", b -> "owner-b".equals(b.get(2)) ? 1L : 0L)
            .onUpdate("WHERE expires_at <", b -> 4L);
    R2dbcInboxStore store = store(db);
    assertFalse(store.markProcessed("c", "m-1", "owner-a", NOW), "late owner A");
    assertFalse(store.release("c", "m-1", "owner-a"), "late owner A");
    assertTrue(store.markProcessed("c", "m-1", "owner-b", NOW));
    assertTrue(store.release("c", "m-2", "owner-b"));
    assertTrue(db.last("SET status = 'PROCESSED'").sql().contains("owner = $4"));
    assertEquals(4, store.purgeExpired(NOW));
  }

  @Test
  void driverFailuresBecomeInboxExceptions() {
    FakeR2dbc db = new FakeR2dbc(); // no rule: every statement fails
    assertThrows(InboxException.class, () -> acquire(store(db), "o"));
  }
}
