package pe.axiz.test.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.core.commons.jdbc.JdbcTransactions;
import pe.axiz.core.inbox.InboxStore.Acquisition.Status;
import pe.axiz.core.inbox.JdbcInboxStore;

class InboxRestartTest {
  @TempDir Path directory;

  @Test
  void reopeningPersistedInboxPreservesProcessedAndInProgressOwnership() throws Exception {
    String url = "jdbc:h2:file:" + directory.resolve("inbox") + ";MODE=PostgreSQL";
    var firstDataSource = new JdbcDataSource();
    firstDataSource.setURL(url);
    try (var connection = firstDataSource.getConnection();
        var statement = connection.createStatement();
        var schema = JdbcInboxStore.class.getResourceAsStream("/axiz/sql/inbox-h2.sql")) {
      assertNotNull(schema);
      for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) statement.execute(sql);
      }
    }
    var now = Instant.parse("2026-11-03T00:00:00Z");
    var first = new JdbcInboxStore(new JdbcTransactions(firstDataSource), "axiz_inbox");
    assertEquals(
        Status.ACQUIRED,
        first
            .tryAcquire("payments", "done", "old", now, now.plusSeconds(10), now.plusSeconds(100))
            .status());
    assertTrue(first.markProcessed("payments", "done", "old", now));
    assertEquals(
        Status.ACQUIRED,
        first
            .tryAcquire(
                "payments", "pending", "old", now, now.plusSeconds(10), now.plusSeconds(100))
            .status());
    // All connections closed: H2 closes its persisted database; a fresh client reopens it.
    var reopenedDataSource = new JdbcDataSource();
    reopenedDataSource.setURL(url);
    var restarted = new JdbcInboxStore(new JdbcTransactions(reopenedDataSource), "axiz_inbox");
    assertEquals(
        Status.PROCESSED,
        restarted
            .tryAcquire("payments", "done", "new", now, now.plusSeconds(10), now.plusSeconds(100))
            .status());
    assertEquals(
        Status.BUSY,
        restarted
            .tryAcquire(
                "payments", "pending", "new", now, now.plusSeconds(10), now.plusSeconds(100))
            .status());
    var later = now.plusSeconds(11);
    assertEquals(
        Status.ACQUIRED,
        restarted
            .tryAcquire(
                "payments", "pending", "new", later, later.plusSeconds(10), now.plusSeconds(100))
            .status());
    assertFalse(first.markProcessed("payments", "pending", "old", later));
    assertTrue(restarted.markProcessed("payments", "pending", "new", later));
    assertEquals(
        Status.PROCESSED,
        restarted
            .tryAcquire(
                "payments", "pending", "replay", later, later.plusSeconds(10), now.plusSeconds(100))
            .status());
  }
}
