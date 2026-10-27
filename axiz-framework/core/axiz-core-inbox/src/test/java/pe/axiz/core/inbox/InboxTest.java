package pe.axiz.core.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.jdbc.ConnectionProvider;
import pe.axiz.core.commons.jdbc.JdbcTransactions;
import pe.axiz.core.messaging.Deduplicator;

class InboxTest {

  private JdbcInboxStore store;
  private JdbcDataSource dataSource;
  private final MutableClock clock = new MutableClock();

  @BeforeEach
  void setUp() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:inbox-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        InputStream in = getClass().getResourceAsStream("/axiz/sql/inbox-h2.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
    store = new JdbcInboxStore(new JdbcTransactions(ds), "axiz_inbox");
    dataSource = ds;
  }

  @Test
  void processesOncePerConsumer() {
    InboxDeduplicator dedup =
        new InboxDeduplicator(store, Duration.ofMinutes(5), Duration.ofDays(7), clock);
    Deduplicator.Claim first = dedup.claim("notifications", "e-1");
    assertEquals(Deduplicator.Claim.Status.ACQUIRED, first.status());
    Deduplicator.Claim concurrent = dedup.claim("notifications", "e-1");
    assertEquals(Deduplicator.Claim.Status.BUSY, concurrent.status(), "in progress elsewhere");
    assertEquals(Duration.ofMinutes(5), concurrent.retryAfter(), "until the lease ends");
    assertTrue(dedup.completed("notifications", "e-1", first.token()));
    assertEquals(
        Deduplicator.Claim.Status.ALREADY_PROCESSED, dedup.claim("notifications", "e-1").status());
    assertEquals(
        Deduplicator.Claim.Status.ACQUIRED,
        dedup.claim("audit-archiver", "e-1").status(),
        "other consumers are independent");
  }

  @Test
  void failureReleasesAndExpiredLeaseIsTakenOver() {
    InboxDeduplicator dedup =
        new InboxDeduplicator(store, Duration.ofMinutes(5), Duration.ofDays(7), clock);
    Deduplicator.Claim a = dedup.claim("c", "e-2");
    assertTrue(dedup.failed("c", "e-2", a.token()));
    Deduplicator.Claim retry = dedup.claim("c", "e-2");
    assertEquals(Deduplicator.Claim.Status.ACQUIRED, retry.status(), "redelivery after failure");
    clock.now = clock.now.plus(Duration.ofMinutes(6));
    Deduplicator.Claim b = dedup.claim("c", "e-2");
    assertEquals(Deduplicator.Claim.Status.ACQUIRED, b.status(), "lease of a dead delivery");
    assertTrue(dedup.completed("c", "e-2", b.token()));
    assertFalse(dedup.failed("c", "e-2", b.token()), "release never removes processed entries");
    assertEquals(Deduplicator.Claim.Status.ALREADY_PROCESSED, dedup.claim("c", "e-2").status());
  }

  @Test
  void aLateDeliveryCannotTouchTheRegistrationOfTheNewOwner() {
    InboxDeduplicator dedup =
        new InboxDeduplicator(store, Duration.ofMinutes(5), Duration.ofDays(7), clock);
    Deduplicator.Claim a = dedup.claim("c", "e-5");
    clock.now = clock.now.plus(Duration.ofMinutes(6)); // A is slow: its lease expires
    Deduplicator.Claim b = dedup.claim("c", "e-5");
    assertEquals(Deduplicator.Claim.Status.ACQUIRED, b.status());
    assertFalse(dedup.failed("c", "e-5", a.token()), "A cannot release B's claim");
    assertFalse(dedup.completed("c", "e-5", a.token()), "A cannot complete B's claim");
    assertEquals(Deduplicator.Claim.Status.BUSY, dedup.claim("c", "e-5").status(), "B holds it");
    assertTrue(dedup.completed("c", "e-5", b.token()));
  }

  @Test
  void worksInsideTransactions() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:inbox-tx-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        InputStream in = getClass().getResourceAsStream("/axiz/sql/inbox-h2.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
    JdbcTransactions tx = new JdbcTransactions(ds);
    JdbcInboxStore txStore = new JdbcInboxStore(tx, "axiz_inbox");
    InboxStore.Acquisition[] results =
        tx.inTransaction(
            () ->
                new InboxStore.Acquisition[] {
                  txStore.tryAcquire(
                      "c",
                      "e-9",
                      "o-1",
                      clock.now,
                      clock.now.plusSeconds(60),
                      clock.now.plusSeconds(600)),
                  txStore.tryAcquire(
                      "c",
                      "e-9",
                      "o-2",
                      clock.now,
                      clock.now.plusSeconds(60),
                      clock.now.plusSeconds(600)),
                  txStore.tryAcquire(
                      "c",
                      "e-10",
                      "o-3",
                      clock.now,
                      clock.now.plusSeconds(60),
                      clock.now.plusSeconds(600))
                });
    assertEquals(InboxStore.Acquisition.Status.ACQUIRED, results[0].status());
    assertEquals(InboxStore.Acquisition.Status.BUSY, results[1].status());
    assertEquals(
        InboxStore.Acquisition.Status.ACQUIRED,
        results[2].status(),
        "the transaction is still usable after a duplicate key");
  }

  @Test
  void purgeAndErrors() {
    assertEquals(
        InboxStore.Acquisition.Status.ACQUIRED,
        store
            .tryAcquire(
                "c", "e-3", "o", clock.now, clock.now.plusSeconds(60), clock.now.plusSeconds(120))
            .status());
    assertEquals(0, store.purgeExpired(clock.now));
    assertEquals(1, store.purgeExpired(clock.now.plusSeconds(121)));
    JdbcTransactions noDb = new JdbcTransactions(new JdbcDataSource());
    JdbcInboxStore broken = new JdbcInboxStore(noDb, "axiz_inbox");
    assertThrows(InboxException.class, () -> broken.purgeExpired(clock.now));
    assertThrows(IllegalArgumentException.class, () -> new JdbcInboxStore(noDb, "Bad Table"));
  }

  @Test
  void aRegistrationReleasedWhileAcquiringIsRedeliveredAtOnce() throws Exception {
    Instant now = clock.now;
    store.tryAcquire("c", "e-9", "a", now, now.plusSeconds(60), now.plusSeconds(600));
    // the owner releases its registration between our insert attempt and our read
    ConnectionProvider racing =
        new ConnectionProvider() {
          @Override
          public Connection get() throws SQLException {
            Connection real = dataSource.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, args) -> {
                      if (m.getName().equals("prepareStatement")
                          && ((String) args[0]).startsWith("SELECT status")) {
                        store.release("c", "e-9", "a");
                      }
                      try {
                        return m.invoke(real, args);
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                    });
          }

          @Override
          public void release(Connection connection) throws SQLException {
            connection.close();
          }
        };
    InboxStore.Acquisition a =
        new JdbcInboxStore(racing, "axiz_inbox")
            .tryAcquire(
                "c", "e-9", "b", now.plusSeconds(1), now.plusSeconds(61), now.plusSeconds(600));
    assertEquals(InboxStore.Acquisition.Status.BUSY, a.status());
    assertEquals(now.plusSeconds(1), a.leaseUntil(), "busy until now: retry right away");
  }

  @Test
  void aConnectionThatCannotBeReturnedDoesNotFailTheOperation() {
    ConnectionProvider leaky =
        new ConnectionProvider() {
          @Override
          public Connection get() throws SQLException {
            return dataSource.getConnection();
          }

          @Override
          public void release(Connection connection) throws SQLException {
            connection.close();
            throw new SQLException("pool closed");
          }
        };
    JdbcInboxStore s = new JdbcInboxStore(leaky, "axiz_inbox");
    Instant now = clock.now;
    assertEquals(
        InboxStore.Acquisition.Status.ACQUIRED,
        s.tryAcquire("c", "e-10", "a", now, now.plusSeconds(60), now.plusSeconds(600)).status());
    assertTrue(s.markProcessed("c", "e-10", "a", now));
  }

  @Test
  void anUnreachableDatabaseFailsTheAcquisition() {
    JdbcInboxStore broken =
        new JdbcInboxStore(new JdbcTransactions(new JdbcDataSource()), "axiz_inbox");
    InboxException e =
        assertThrows(
            InboxException.class,
            () ->
                broken.tryAcquire(
                    "c",
                    "e-1",
                    "a",
                    clock.now,
                    clock.now.plusSeconds(1),
                    clock.now.plusSeconds(2)));
    assertTrue(e.getMessage().startsWith("Inbox acquire failed"), e.getMessage());
  }

  @Test
  void theDeduplicatorNeedsAPositiveLeaseAndWaitsAFullLeaseWhenTheEndIsUnknown() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InboxDeduplicator(store, Duration.ZERO, Duration.ofHours(1), clock));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InboxDeduplicator(store, Duration.ofSeconds(-1), Duration.ofHours(1), clock));
    InboxStore unknownLease =
        new InboxStore() {
          @Override
          public Acquisition tryAcquire(
              String consumer,
              String messageId,
              String owner,
              Instant now,
              Instant leaseUntil,
              Instant expiresAt) {
            return Acquisition.busy(null);
          }

          @Override
          public boolean markProcessed(
              String consumer, String messageId, String owner, Instant at) {
            return false;
          }

          @Override
          public boolean release(String consumer, String messageId, String owner) {
            return false;
          }

          @Override
          public int purgeExpired(Instant now) {
            return 0;
          }
        };
    unknownLease.verifySchema(); // nothing to check by default
    Deduplicator.Claim busy =
        new InboxDeduplicator(unknownLease, Duration.ofSeconds(30), Duration.ofHours(1), clock)
            .claim("c", "e-1");
    assertEquals(Deduplicator.Claim.Status.BUSY, busy.status());
    assertEquals(Duration.ofSeconds(30), busy.retryAfter());
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /**
   * Regression: a database created with the previous layout (no owner column) is detected at
   * startup, upgraded by a new migration and keeps its data and invariants.
   */
  @Test
  void anInboxFromThePreviousVersionIsDetectedAndUpgradedKeepingItsData() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:inbox-old-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE axiz_inbox (consumer VARCHAR(100) NOT NULL, message_id VARCHAR(128) NOT"
              + " NULL, status VARCHAR(16) NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT"
              + " NULL, lease_until TIMESTAMP WITH TIME ZONE NOT NULL, processed_at TIMESTAMP"
              + " WITH TIME ZONE, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY"
              + " (consumer, message_id))");
      s.execute(
          "INSERT INTO axiz_inbox VALUES ('c', 'done', 'PROCESSED', TIMESTAMP WITH TIME ZONE"
              + " '2026-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2026-01-01 00:05:00+00',"
              + " TIMESTAMP WITH TIME ZONE '2026-01-01 00:01:00+00', TIMESTAMP WITH TIME ZONE"
              + " '2026-02-01 00:00:00+00')");
      s.execute(
          "INSERT INTO axiz_inbox VALUES ('c', 'stuck', 'IN_PROGRESS', TIMESTAMP WITH TIME ZONE"
              + " '2026-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2026-01-01 00:05:00+00',"
              + " NULL, TIMESTAMP WITH TIME ZONE '2026-02-01 00:00:00+00')");
    }
    JdbcInboxStore old = new JdbcInboxStore(new JdbcTransactions(ds), "axiz_inbox");
    InboxException e = assertThrows(InboxException.class, old::verifySchema);
    assertTrue(e.getMessage().contains("inbox-postgresql-upgrade-owner.sql"), e.getMessage());

    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        InputStream in =
            getClass().getResourceAsStream("/axiz/sql/inbox-postgresql-upgrade-owner.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
    JdbcInboxStore upgraded = new JdbcInboxStore(new JdbcTransactions(ds), "axiz_inbox");
    upgraded.verifySchema();
    Instant later = t0.plusSeconds(600);
    assertEquals(
        InboxStore.Acquisition.Status.PROCESSED,
        upgraded
            .tryAcquire("c", "done", "x", later, later.plusSeconds(60), later.plusSeconds(3600))
            .status(),
        "processed messages stay processed");
    assertEquals(
        InboxStore.Acquisition.Status.ACQUIRED,
        upgraded
            .tryAcquire("c", "stuck", "new", later, later.plusSeconds(60), later.plusSeconds(3600))
            .status(),
        "an ownerless lease of the old version is taken over when it ends");
    assertTrue(upgraded.markProcessed("c", "stuck", "new", later));
  }
}
