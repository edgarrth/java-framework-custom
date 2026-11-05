package pe.axiz.adapter.sequence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import pe.axiz.kit.jdbc.JdbcTransactions;

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
}
