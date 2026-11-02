package pe.axiz.core.sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.jdbc.JdbcTransactions;

class SequenceTest {

  static final ZoneId LIMA = ZoneId.of("America/Lima");

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-31T15:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      Instant fixed = now;
      return Clock.fixed(fixed, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void formatsAndResetsDaily() {
    MutableClock clock = new MutableClock();
    SequenceGenerator gen =
        new SequenceGenerator(
            "operation",
            new SequenceFormat("OP-", 6, ResetPolicy.DAILY, "-", LIMA),
            new InMemorySequenceStore(),
            1,
            clock);
    assertEquals("OP-20260131-000001", gen.next());
    assertEquals("OP-20260131-000002", gen.next());
    clock.now = Instant.parse("2026-02-01T06:00:00Z"); // 01:00 in Lima
    assertEquals("OP-20260201-000001", gen.next());
  }

  @Test
  void blocksReduceRoundTripsAndNeverRepeat() {
    InMemorySequenceStore store = new InMemorySequenceStore();
    SequenceGenerator a =
        new SequenceGenerator(
            "ticket",
            new SequenceFormat("T", 8, ResetPolicy.NEVER, "-", LIMA),
            store,
            10,
            new MutableClock());
    SequenceGenerator b =
        new SequenceGenerator(
            "ticket",
            new SequenceFormat("T", 8, ResetPolicy.NEVER, "-", LIMA),
            store,
            10,
            new MutableClock());
    assertEquals(1, a.nextValue());
    assertEquals(11, b.nextValue());
    assertEquals(2, a.nextValue());
    assertEquals("T00000003", a.next());
  }

  @Test
  void exhaustedPeriodFails() {
    SequenceFormat f = new SequenceFormat("X", 2, ResetPolicy.DAILY, "", LIMA);
    assertEquals("X2026013199", f.render("20260131", 99));
    assertThrows(SequenceExhaustedException.class, () -> f.render("20260131", 100));
    assertEquals("Y100", new SequenceFormat("Y", 2, ResetPolicy.NEVER, "", LIMA).render("", 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SequenceFormat("X", 0, ResetPolicy.NEVER, "", LIMA));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SequenceGenerator(
                "Bad Name", f, new InMemorySequenceStore(), 1, Clock.systemUTC()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SequenceGenerator("ok", f, new InMemorySequenceStore(), 0, Clock.systemUTC()));
  }

  @Test
  void jdbcStoreIsAtomicUnderConcurrency() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL(
        "jdbc:h2:mem:seq-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        InputStream in = getClass().getResourceAsStream("/axiz/sql/sequence-h2.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
    JdbcTransactions tx = new JdbcTransactions(ds);
    JdbcSequenceStore store = new JdbcSequenceStore(tx, tx, "axiz_sequence");
    Set<Long> values = ConcurrentHashMap.newKeySet();
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 200; i++) {
        executor.submit(() -> values.add(store.increment("op", "20260131", 1)));
      }
    }
    assertEquals(200, values.size());
    assertEquals(200L, values.stream().mapToLong(Long::longValue).max().orElseThrow());
    assertEquals(5, store.increment("op", "20260201", 5));
  }

  @Test
  void formattedValueUsesTheSamePeriodAsItsReservation() {
    java.util.concurrent.atomic.AtomicInteger reads =
        new java.util.concurrent.atomic.AtomicInteger();
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return reads.getAndIncrement() == 0
                ? Instant.parse("2026-01-31T23:59:59Z")
                : Instant.parse("2026-02-01T00:00:00Z");
          }
        };
    java.util.List<String> periods = new java.util.ArrayList<>();
    SequenceGenerator generator =
        new SequenceGenerator(
            "operation",
            new SequenceFormat("OP-", 2, ResetPolicy.DAILY, "-", ZoneOffset.UTC),
            (name, period, increment) -> {
              periods.add(period);
              return 1;
            },
            1,
            clock);
    assertEquals("OP-20260131-01", generator.next());
    assertEquals(java.util.List.of("20260131"), periods);
    assertEquals(1, reads.get());
  }

  @Test
  void exhaustedLongCounterNeverWrapsTheCachedRange() {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    SequenceGenerator generator =
        new SequenceGenerator(
            "operation",
            new SequenceFormat("OP-", 19, ResetPolicy.NEVER, "-", LIMA),
            (name, period, increment) -> {
              if (calls.incrementAndGet() == 1) return Long.MAX_VALUE;
              throw new ArithmeticException("exhausted");
            },
            2,
            new MutableClock());
    assertEquals(Long.MAX_VALUE - 1, generator.nextValue());
    assertEquals(Long.MAX_VALUE, generator.nextValue());
    assertThrows(ArithmeticException.class, generator::nextValue);
    InMemorySequenceStore memory = new InMemorySequenceStore();
    assertThrows(IllegalArgumentException.class, () -> memory.increment("operation", "", 0));
    assertThrows(IllegalArgumentException.class, () -> memory.increment("operation", "", -1));
    assertEquals(1, memory.increment("operation", "", 1));
    assertThrows(
        IllegalStateException.class,
        () ->
            new SequenceGenerator(
                    "operation",
                    new SequenceFormat("", 2, ResetPolicy.NEVER, "", LIMA),
                    (name, period, increment) -> 0,
                    1,
                    new MutableClock())
                .nextValue());
  }
}
