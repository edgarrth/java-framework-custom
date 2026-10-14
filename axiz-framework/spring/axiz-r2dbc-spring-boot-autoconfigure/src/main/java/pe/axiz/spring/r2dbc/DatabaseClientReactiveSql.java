package pe.axiz.spring.r2dbc;

import java.util.Objects;
import org.springframework.r2dbc.core.DatabaseClient;
import pe.axiz.core.persistence.r2dbc.R2dbcRow;
import pe.axiz.core.persistence.r2dbc.ReactiveSql;
import pe.axiz.core.persistence.r2dbc.RowMapper;
import pe.axiz.core.persistence.r2dbc.SqlParams;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link ReactiveSql} over Spring's {@link DatabaseClient}: statements join the reactive
 * transaction of the caller ({@code @Transactional} or {@code TransactionalOperator}) and are
 * observed by the R2DBC instrumentation.
 */
public final class DatabaseClientReactiveSql implements ReactiveSql {

  private final DatabaseClient client;

  public DatabaseClientReactiveSql(DatabaseClient client) {
    this.client = Objects.requireNonNull(client);
  }

  @Override
  public Mono<Long> update(String sql, SqlParams params) {
    return bind(client.sql(sql), params).fetch().rowsUpdated();
  }

  @Override
  public <T> Flux<T> query(String sql, SqlParams params, RowMapper<T> mapper) {
    return bind(client.sql(sql), params).map((row, meta) -> mapper.map(new R2dbcRow(row))).all();
  }

  static DatabaseClient.GenericExecuteSpec bind(
      DatabaseClient.GenericExecuteSpec spec, SqlParams params) {
    DatabaseClient.GenericExecuteSpec s = spec;
    for (SqlParams.Param p : params.params()) {
      Object value = p.value();
      s = value == null ? s.bindNull(p.name(), p.type()) : s.bind(p.name(), value);
    }
    return s;
  }

  @Override
  public String toString() {
    return "DatabaseClientReactiveSql";
  }
}
