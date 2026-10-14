package pe.axiz.core.test.r2dbc;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.TransactionDefinition;
import io.r2dbc.spi.ValidationDepth;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * In-memory R2DBC driver for unit tests, reporting itself as PostgreSQL ({@code $n} bind markers):
 * statements are matched by a regular expression on their SQL and answered by scripted functions of
 * the bound values; every execution, bind and transaction event is recorded for assertions.
 *
 * <pre>
 * FakeR2dbc db = new FakeR2dbc()
 *     .onUpdate("INSERT INTO axiz_outbox", binds -> 1L)
 *     .onQuery("SELECT .* FROM axiz_outbox", binds -> List.of(Map.of("id", "e-1")));
 * </pre>
 */
public final class FakeR2dbc implements ConnectionFactory {

  /**
   * One executed statement.
   *
   * @param sql SQL as sent to the driver (positional placeholders)
   * @param binds bound values by position (null for {@code bindNull})
   * @param connection id of the connection that ran it
   */
  public record Execution(String sql, Map<Integer, @Nullable Object> binds, int connection) {}

  private record Rule(
      Pattern pattern,
      @Nullable Function<Map<Integer, @Nullable Object>, Long> update,
      @Nullable Function<Map<Integer, @Nullable Object>, List<Map<String, Object>>> query) {}

  private final List<Rule> rules = new CopyOnWriteArrayList<>();
  private final List<Execution> executions = new CopyOnWriteArrayList<>();
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final AtomicInteger connections = new AtomicInteger();
  private final java.util.Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * Makes a connection operation fail after being recorded: {@code begin}, {@code commit}, {@code
   * rollback} or {@code close}.
   */
  public FakeR2dbc failOn(String operation) {
    failing.add(operation);
    return this;
  }

  /** Answers matching statements with an update count. */
  public FakeR2dbc onUpdate(String regex, Function<Map<Integer, @Nullable Object>, Long> answer) {
    rules.add(new Rule(Pattern.compile(regex, Pattern.DOTALL), answer, null));
    return this;
  }

  /** Answers matching statements with rows (column name to value). */
  public FakeR2dbc onQuery(
      String regex, Function<Map<Integer, @Nullable Object>, List<Map<String, Object>>> answer) {
    rules.add(new Rule(Pattern.compile(regex, Pattern.DOTALL), null, answer));
    return this;
  }

  public List<Execution> executions() {
    return List.copyOf(executions);
  }

  /** Transaction and connection events: {@code open:1}, {@code begin:1}, {@code commit:1}... */
  public List<String> events() {
    return List.copyOf(events);
  }

  /** The last execution whose SQL contains the text. */
  public Execution last(String sqlFragment) {
    for (int i = executions.size() - 1; i >= 0; i--) {
      if (executions.get(i).sql().contains(sqlFragment)) {
        return executions.get(i);
      }
    }
    throw new AssertionError("No statement containing " + sqlFragment + " in " + executions);
  }

  @Override
  public Publisher<? extends Connection> create() {
    return Mono.fromSupplier(() -> new FakeConnection(connections.incrementAndGet()));
  }

  @Override
  public ConnectionFactoryMetadata getMetadata() {
    // PostgreSQL, so Spring resolves $n bind markers and the Postgres dialect
    return () -> "PostgreSQL";
  }

  private Rule rule(String sql) {
    for (Rule r : rules) {
      if (r.pattern().matcher(sql).find()) {
        return r;
      }
    }
    throw new AssertionError("Unexpected SQL (no rule matches): " + sql);
  }

  private final class FakeConnection implements Connection {
    private final int id;

    FakeConnection(int id) {
      this.id = id;
      events.add("open:" + id);
    }

    private Publisher<Void> event(String name) {
      return Mono.defer(
          () -> {
            events.add(name + ":" + id);
            return failing.contains(name)
                ? Mono.<Void>error(new IllegalStateException(name + " failed"))
                : Mono.<Void>empty();
          });
    }

    @Override
    public Publisher<Void> beginTransaction() {
      return event("begin");
    }

    @Override
    public Publisher<Void> beginTransaction(TransactionDefinition definition) {
      return event("begin");
    }

    @Override
    public Publisher<Void> close() {
      return event("close");
    }

    @Override
    public Publisher<Void> commitTransaction() {
      return event("commit");
    }

    @Override
    public Batch createBatch() {
      throw new UnsupportedOperationException("batches");
    }

    @Override
    public Publisher<Void> createSavepoint(String name) {
      return event("savepoint");
    }

    @Override
    public Statement createStatement(String sql) {
      return new FakeStatement(sql, id);
    }

    @Override
    public boolean isAutoCommit() {
      return true;
    }

    @Override
    public ConnectionMetadata getMetadata() {
      return new ConnectionMetadata() {
        @Override
        public String getDatabaseProductName() {
          return "PostgreSQL";
        }

        @Override
        public String getDatabaseVersion() {
          return "1";
        }
      };
    }

    @Override
    public IsolationLevel getTransactionIsolationLevel() {
      return IsolationLevel.READ_COMMITTED;
    }

    @Override
    public Publisher<Void> releaseSavepoint(String name) {
      return Mono.empty();
    }

    @Override
    public Publisher<Void> rollbackTransaction() {
      return event("rollback");
    }

    @Override
    public Publisher<Void> rollbackTransactionToSavepoint(String name) {
      return Mono.empty();
    }

    @Override
    public Publisher<Void> setAutoCommit(boolean autoCommit) {
      return Mono.empty();
    }

    @Override
    public Publisher<Void> setLockWaitTimeout(Duration timeout) {
      return Mono.empty();
    }

    @Override
    public Publisher<Void> setStatementTimeout(Duration timeout) {
      return Mono.empty();
    }

    @Override
    public Publisher<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
      return Mono.empty();
    }

    @Override
    public Publisher<Boolean> validate(ValidationDepth depth) {
      return Mono.just(true);
    }
  }

  private final class FakeStatement implements Statement {
    private final String sql;
    private final int connection;
    private final Map<Integer, @Nullable Object> binds = new LinkedHashMap<>();

    FakeStatement(String sql, int connection) {
      this.sql = sql;
      this.connection = connection;
    }

    @Override
    public Statement add() {
      throw new UnsupportedOperationException("batches");
    }

    @Override
    public Statement bind(int index, Object value) {
      binds.put(index, Objects.requireNonNull(value));
      return this;
    }

    @Override
    public Statement bind(String name, Object value) {
      throw new UnsupportedOperationException("named binds: use positions");
    }

    @Override
    public Statement bindNull(int index, Class<?> type) {
      binds.put(index, null);
      return this;
    }

    @Override
    public Statement bindNull(String name, Class<?> type) {
      throw new UnsupportedOperationException("named binds: use positions");
    }

    @Override
    public Publisher<? extends Result> execute() {
      return Mono.fromSupplier(
          () -> {
            Map<Integer, @Nullable Object> copy =
                Collections.unmodifiableMap(new LinkedHashMap<>(binds));
            executions.add(new Execution(sql, copy, connection));
            Rule r = rule(sql);
            if (r.update() != null) {
              return new FakeResult(r.update().apply(copy), List.of());
            }
            return new FakeResult(0L, Objects.requireNonNull(r.query()).apply(copy));
          });
    }
  }

  private static final class FakeResult implements Result {
    private final long updated;
    private final List<Map<String, Object>> rows;

    FakeResult(long updated, List<Map<String, Object>> rows) {
      this.updated = updated;
      this.rows = new ArrayList<>(rows);
    }

    @Override
    public Publisher<Long> getRowsUpdated() {
      return Mono.just(updated);
    }

    @Override
    public <T> Publisher<T> map(BiFunction<Row, RowMetadata, ? extends T> mappingFunction) {
      return Flux.fromIterable(rows).map(r -> mappingFunction.apply(new FakeRow(r), new Meta()));
    }

    @Override
    public Result filter(Predicate<Segment> filter) {
      return this;
    }

    @Override
    public <T> Publisher<T> flatMap(
        Function<Segment, ? extends Publisher<? extends T>> mappingFunction) {
      throw new UnsupportedOperationException("segments");
    }
  }

  private record FakeRow(Map<String, Object> values) implements Row {
    @Override
    public RowMetadata getMetadata() {
      return new Meta();
    }

    @Override
    public <T> @Nullable T get(int index, Class<T> type) {
      return convert(new ArrayList<>(values.values()).get(index), type);
    }

    @Override
    public <T> @Nullable T get(String name, Class<T> type) {
      return convert(values.get(name), type);
    }

    private static <T> @Nullable T convert(@Nullable Object value, Class<T> type) {
      if (value == null) {
        return null;
      }
      if (type == Long.class && value instanceof Number n) {
        return type.cast(n.longValue());
      }
      if (type == Integer.class && value instanceof Number n) {
        return type.cast(n.intValue());
      }
      return type.cast(value);
    }
  }

  private static final class Meta implements RowMetadata {
    @Override
    public ColumnMetadata getColumnMetadata(int index) {
      throw new UnsupportedOperationException("metadata");
    }

    @Override
    public ColumnMetadata getColumnMetadata(String name) {
      throw new UnsupportedOperationException("metadata");
    }

    @Override
    public List<? extends ColumnMetadata> getColumnMetadatas() {
      return List.of();
    }

    @Override
    public boolean contains(String columnName) {
      return false;
    }
  }
}
