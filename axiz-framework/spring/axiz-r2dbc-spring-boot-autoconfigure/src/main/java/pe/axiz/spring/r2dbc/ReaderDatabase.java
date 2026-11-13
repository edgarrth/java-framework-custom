package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.Closeable;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.r2dbc.core.DatabaseClient;
import pe.axiz.kit.r2dbc.ReactiveSql;
import reactor.core.publisher.Mono;

/**
 * The reader endpoint of the Aurora cluster ({@code axiz.r2dbc.reader.url}): a separate pool for
 * queries that tolerate replica lag. It is deliberately not a {@link ConnectionFactory} bean, so
 * the writer stays the only candidate for transactions, repositories and migrations; statements run
 * here never take part in a transaction.
 */
public final class ReaderDatabase implements DisposableBean {

  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

  private final ConnectionFactory connectionFactory;
  private final @Nullable AutoCloseable credentials;
  private final DatabaseClient client;
  private final ReactiveSql sql;

  public ReaderDatabase(ConnectionFactory connectionFactory) {
    this(connectionFactory, null);
  }

  /** Owns reader-specific IAM resources as well as the connection pool. */
  public ReaderDatabase(ConnectionFactory connectionFactory, @Nullable AutoCloseable credentials) {
    this.credentials = credentials;
    this.connectionFactory = Objects.requireNonNull(connectionFactory);
    this.client = DatabaseClient.create(connectionFactory);
    this.sql = new DatabaseClientReactiveSql(client);
  }

  /** Spring's client on the reader pool. */
  public DatabaseClient client() {
    return client;
  }

  /** Named-parameter SQL on the reader pool. */
  public ReactiveSql sql() {
    return sql;
  }

  public ConnectionFactory connectionFactory() {
    return connectionFactory;
  }

  @Override
  public void destroy() {
    Throwable failure = null;
    try {
      if (connectionFactory instanceof Closeable closeable)
        Mono.from(closeable.close()).block(CLOSE_TIMEOUT);
    } catch (RuntimeException | Error problem) {
      failure = problem;
    }
    if (credentials != null) {
      try {
        credentials.close();
      } catch (Exception problem) {
        if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
        var cleanup =
            new IllegalStateException("Could not close reader credential resources", problem);
        if (failure == null) failure = cleanup;
        else failure.addSuppressed(cleanup);
      } catch (Error problem) {
        if (failure == null) failure = problem;
        else if (failure != problem) failure.addSuppressed(problem);
      }
    }
    if (failure instanceof RuntimeException problem) throw problem;
    if (failure instanceof Error problem) throw problem;
  }
}
