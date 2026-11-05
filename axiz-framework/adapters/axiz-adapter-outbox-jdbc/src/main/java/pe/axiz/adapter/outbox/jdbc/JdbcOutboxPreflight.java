package pe.axiz.adapter.outbox.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.outbox.OutboxException;
import pe.axiz.kit.jdbc.SqlStates;

/** Read-only upgrade diagnosis; works before and after the aggregate-sequence migration. */
public final class JdbcOutboxPreflight {
  private JdbcOutboxPreflight() {}

  /** Historical failure and the successors that require an explicit operational decision. */
  public record Failure(
      String id,
      String aggregateType,
      String aggregateId,
      String destination,
      int attempts,
      @Nullable String error,
      boolean publishedSuccessors,
      boolean pendingSuccessors) {}

  /** Streams the canonical SQL report without changing statuses or retaining all rows in memory. */
  public static long report(Connection connection, String table, Consumer<Failure> result)
      throws SQLException {
    String identifier = SqlStates.identifier(table);
    Objects.requireNonNull(result);
    String sql;
    try (var input =
        Objects.requireNonNull(
            JdbcOutboxPreflight.class.getResourceAsStream(
                "/axiz/sql/outbox-postgresql-upgrade-preflight.sql"))) {
      sql =
          new String(input.readAllBytes(), StandardCharsets.UTF_8)
              .replace("FROM axiz_outbox", "FROM " + identifier);
    } catch (IOException failure) {
      throw new OutboxException("Cannot read outbox preflight SQL", failure);
    }
    long count = 0;
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setFetchSize(200);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          result.accept(
              new Failure(
                  rows.getString("id"),
                  rows.getString("aggregate_type"),
                  rows.getString("aggregate_id"),
                  rows.getString("destination"),
                  rows.getInt("attempts"),
                  rows.getString("last_error"),
                  rows.getBoolean("published_successors"),
                  rows.getBoolean("pending_successors")));
          count++;
        }
      }
    }
    return count;
  }
}
