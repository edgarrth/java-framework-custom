package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import pe.axiz.core.commons.jdbc.SqlStates;
import reactor.core.publisher.Mono;

/** Atomic PostgreSQL counter blocks, using an independent connection and transaction. */
public final class R2dbcSequenceStore implements ReactiveSequenceStore {
  private final R2dbcSql sql;
  private final String table;

  public R2dbcSequenceStore(ConnectionFactory connections, String table) {
    this.sql = new R2dbcSql(connections);
    this.table = SqlStates.identifier(table);
  }

  @Override
  public Mono<Long> increment(String name, String period, int increment) {
    return Mono.defer(
        () -> {
          if (increment <= 0)
            return Mono.error(new IllegalArgumentException("increment must be positive"));
          return sql.inTransaction(
              reserved ->
                  reserved
                      .query(
                          "INSERT INTO "
                              + table
                              + " (seq_name, seq_period, seq_value) VALUES (:name, :period, :increment) "
                              + "ON CONFLICT (seq_name, seq_period) DO UPDATE SET seq_value = "
                              + table
                              + ".seq_value + EXCLUDED.seq_value RETURNING seq_value",
                          SqlParams.of()
                              .bind("name", name)
                              .bind("period", period)
                              .bind("increment", increment),
                          row -> row.require("seq_value", Long.class))
                      .single());
        });
  }
}
