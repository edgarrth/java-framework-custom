package pe.axiz.kit.r2dbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * IAM authentication tokens for Aurora PostgreSQL. A token is valid for 15 minutes; it is reused
 * until {@code refreshAfter} and then generated again (signed locally with the credentials of the
 * pod, IRSA or EKS Pod Identity: no call to AWS). Connections already open stay valid after their
 * token expires; the pool's maximum lifetime renews them.
 */
public final class RdsIamPasswordProvider implements DatabasePasswordProvider, AutoCloseable {

  /** Lifetime of an RDS authentication token. */
  public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

  private final RdsUtilities rds;
  private final String host;
  private final int port;
  private final String username;
  private final Clock clock;
  private final Duration refreshAfter;
  private final AtomicReference<@Nullable Token> cached = new AtomicReference<>();
  private final Runnable closeAction;

  private record Token(String value, Instant generatedAt) {}

  public RdsIamPasswordProvider(
      RdsUtilities rds,
      String host,
      int port,
      String username,
      Clock clock,
      Duration refreshAfter) {
    this(rds, host, port, username, clock, refreshAfter, () -> {});
  }

  /** With lifecycle ownership of the warmed credential provider used by the signer. */
  public RdsIamPasswordProvider(
      RdsUtilities rds,
      String host,
      int port,
      String username,
      Clock clock,
      Duration refreshAfter,
      Runnable closeAction) {
    this.closeAction = Objects.requireNonNull(closeAction);
    this.rds = Objects.requireNonNull(rds);
    this.host = Objects.requireNonNull(host);
    this.port = port;
    this.username = Objects.requireNonNull(username);
    this.clock = Objects.requireNonNull(clock);
    if (refreshAfter.isNegative() || refreshAfter.compareTo(TOKEN_LIFETIME) >= 0) {
      throw new IllegalArgumentException("refreshAfter must be shorter than " + TOKEN_LIFETIME);
    }
    this.refreshAfter = refreshAfter;
  }

  @Override
  public CharSequence password() {
    Instant now = clock.instant();
    Token current = cached.get();
    if (current != null && now.isBefore(current.generatedAt().plus(refreshAfter)))
      return current.value();
    String value =
        rds.generateAuthenticationToken(
            builder -> builder.hostname(host).port(port).username(username));
    Token fresh = new Token(value, now);
    if (cached.compareAndSet(current, fresh)) return value;
    Token winner = cached.get();
    return winner == null ? value : winner.value();
  }

  @Override
  public void close() {
    closeAction.run();
  }

  @Override
  public String toString() {
    return "RdsIamPasswordProvider[" + username + "@" + host + ":" + port + "]";
  }
}
