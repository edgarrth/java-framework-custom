package pe.axiz.adapter.outbox.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.outbox.OutboxException;
import pe.axiz.kit.sql.SqlIdentifiers;

/** Read-only diagnosis of failed events and their successors in the current v2 schema. */
public final class JdbcOutboxPreflight {
  private JdbcOutboxPreflight() {}

  /** A failure and the successors that require an explicit operational decision. */
  public record Failure(
      String id,
      String aggregateType,
      String aggregateId,
      String destination,
      int attempts,
      @Nullable String error,
      boolean publishedSuccessors,
      boolean pendingSuccessors) {
    /** Named construction; required values and invariants are checked by build. */
    public static Builder builder() {
      return new Builder();
    }

    /** Mutable construction state; use one builder per operation, never as shared state. */
    public static final class Builder {
      private @Nullable String id = null;
      private @Nullable String aggregateType = null;
      private @Nullable String aggregateId = null;
      private @Nullable String destination = null;
      private int attempts = 0;
      private @Nullable String error = null;
      private boolean publishedSuccessors = false;
      private boolean pendingSuccessors = false;

      private Builder() {}

      public Builder id(String value) {
        this.id = value;
        return this;
      }

      public Builder aggregateType(String value) {
        this.aggregateType = value;
        return this;
      }

      public Builder aggregateId(String value) {
        this.aggregateId = value;
        return this;
      }

      public Builder destination(String value) {
        this.destination = value;
        return this;
      }

      public Builder attempts(int value) {
        this.attempts = value;
        return this;
      }

      public Builder error(@Nullable String value) {
        this.error = value;
        return this;
      }

      public Builder publishedSuccessors(boolean value) {
        this.publishedSuccessors = value;
        return this;
      }

      public Builder pendingSuccessors(boolean value) {
        this.pendingSuccessors = value;
        return this;
      }

      public Failure build() {
        return new Failure(
            Objects.requireNonNull(id, "id"),
            Objects.requireNonNull(aggregateType, "aggregateType"),
            Objects.requireNonNull(aggregateId, "aggregateId"),
            Objects.requireNonNull(destination, "destination"),
            attempts,
            error,
            publishedSuccessors,
            pendingSuccessors);
      }
    }
  }

  /** Streams the canonical SQL report without changing statuses or retaining all rows in memory. */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification =
          "Only table identifiers validated by SqlIdentifiers.require enter SQL text; row "
              + "values use PreparedStatement bindings. This private SQL path accepts no "
              + "caller-provided SQL.")
  public static long report(Connection connection, String table, Consumer<Failure> result)
      throws SQLException {
    String identifier = SqlIdentifiers.require(table);
    Objects.requireNonNull(result);
    String sql;
    try (var input =
        Objects.requireNonNull(
            JdbcOutboxPreflight.class.getResourceAsStream(
                "/axiz/sql/outbox-postgresql-diagnostics.sql"))) {
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
              Failure.builder()
                  .id(rows.getString("id"))
                  .aggregateType(rows.getString("aggregate_type"))
                  .aggregateId(rows.getString("aggregate_id"))
                  .destination(rows.getString("destination"))
                  .attempts(rows.getInt("attempts"))
                  .error(rows.getString("last_error"))
                  .publishedSuccessors(rows.getBoolean("published_successors"))
                  .pendingSuccessors(rows.getBoolean("pending_successors"))
                  .build());
          count++;
        }
      }
    }
    return count;
  }
}
