package pe.axiz.adapter.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.kit.jdbc.ConnectionProvider;

class JdbcAuditChainStateTest {
  @Test
  void chainRequiresSharedTransactionResourceAndBoundedStatementTimeout() throws Exception {
    var source = database();
    var transactions =
        new ConnectionProvider() {
          @Override
          public Object transactionResource() {
            return source;
          }

          @Override
          public Connection get() throws SQLException {
            return source.getConnection();
          }

          @Override
          public void release(Connection connection) throws SQLException {
            connection.close();
          }
        };
    for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(61)))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new JdbcAuditChainState(
                  transactions,
                  source,
                  "axiz_audit_chain",
                  Dialect.H2,
                  Clock.systemUTC(),
                  timeout));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JdbcAuditChainState(
                transactions,
                database(),
                "axiz_audit_chain",
                Dialect.H2,
                Clock.systemUTC(),
                Duration.ofSeconds(1)));
    var state =
        new JdbcAuditChainState(
            transactions,
            source,
            "axiz_audit_chain",
            Dialect.H2,
            Clock.systemUTC(),
            Duration.ofMinutes(1));
    assertEquals(0, state.checkpoint("new").sequence());
  }

  private static JdbcDataSource database() throws SQLException {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE axiz_audit_chain (chain_id VARCHAR(256) PRIMARY KEY, "
              + "last_sequence BIGINT NOT NULL, last_hash VARCHAR(64) NOT NULL,"
              + " updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
      statement.execute("CREATE TABLE evidence (id VARCHAR(64) PRIMARY KEY)");
    }
    return source;
  }

  private static JdbcAuditChainState state(JdbcDataSource source, Connection transaction) {
    return new JdbcAuditChainState(
        new ConnectionProvider() {
          @Override
          public Object transactionResource() {
            return source;
          }

          @Override
          public Connection get() {
            return transaction;
          }

          @Override
          public void release(Connection connection) {
            /* Owned by the surrounding transaction. */
          }
        },
        source,
        "axiz_audit_chain",
        Dialect.H2,
        Clock.systemUTC(),
        Duration.ofSeconds(2));
  }

  private static AuditEvent event(String id) {
    return AuditEvent.builder()
        .id(id)
        .occurredAt(Instant.EPOCH)
        .service("svc")
        .action("change")
        .resourceType("account")
        .resourceId("1")
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.COMMITTED)
        .operationOutcome(TransactionOutcome.COMMITTED)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void rollbackRestoresHeadAndCheckpointNeverObservesUncommittedState() throws Exception {
    var source = database();
    try (var transaction = source.getConnection()) {
      var state = state(source, transaction);
      var sealer = new AuditSealer("durable", AuditSealer.Mode.HASH, state, null, null);
      assertThrows(IllegalStateException.class, () -> sealer.seal(event("no-tx")));
      transaction.setAutoCommit(false);
      var rolledBack = sealer.seal(event("rolled-back"));
      assertEquals(1, rolledBack.integrity().sequence());
      assertEquals(0, state.checkpoint("durable").sequence());
      transaction.rollback();
      var committed = sealer.seal(event("committed"));
      assertEquals(1, committed.integrity().sequence());
      assertNotEquals(rolledBack.integrity().hash(), committed.integrity().hash());
      assertEquals(0, state.checkpoint("durable").sequence());
      try (var insert = transaction.prepareStatement("INSERT INTO evidence (id) VALUES (?)")) {
        insert.setString(1, committed.deliveryId());
        insert.executeUpdate();
      }
      transaction.commit();
      var checkpoint = state.checkpoint("durable");
      assertEquals(1, checkpoint.sequence());
      assertEquals(committed.integrity().hash(), checkpoint.hash());
      assertEquals(
          AuditChainVerifier.Completeness.COMPLETE_UP_TO,
          AuditChainVerifier.verify(List.of(committed), null, checkpoint).completeness());
      try (var connection = source.getConnection();
          var query = connection.createStatement();
          var rows = query.executeQuery("SELECT COUNT(*) FROM evidence")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  @Test
  void independentTransactionsSerializeTheSameChainAndRollbackDoesNotLeaveHoles() throws Exception {
    var source = database();
    var received = new java.util.concurrent.ConcurrentLinkedQueue<AuditEvent>();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<java.util.concurrent.Future<?>>();
      for (int n = 0; n < 100; n++) {
        int id = n;
        tasks.add(
            executor.submit(
                () -> {
                  try (var transaction = source.getConnection()) {
                    transaction.setAutoCommit(false);
                    var state = state(source, transaction);
                    var sealer =
                        new AuditSealer("shared", AuditSealer.Mode.HASH, state, null, null);
                    var sealed = sealer.seal(event(Integer.toString(id)));
                    if (id % 5 == 0) transaction.rollback();
                    else {
                      transaction.commit();
                      received.add(sealed);
                    }
                  } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                  }
                }));
      }
      for (var task : tasks) task.get();
    }
    try (var transaction = source.getConnection()) {
      var checkpoint = state(source, transaction).checkpoint("shared");
      assertEquals(80, checkpoint.sequence());
      var result = AuditChainVerifier.verify(received, null, checkpoint);
      assertEquals(AuditChainVerifier.IntegrityStatus.INTACT, result.integrity());
      assertEquals(AuditChainVerifier.Completeness.COMPLETE_UP_TO, result.completeness());
    }
  }
}
