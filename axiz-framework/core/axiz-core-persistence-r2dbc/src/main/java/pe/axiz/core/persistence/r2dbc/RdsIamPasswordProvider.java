package pe.axiz.core.persistence.r2dbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * IAM authentication tokens for Aurora PostgreSQL. A token is valid for 15 minutes; it is reused
 * until {@code refreshAfter} and then generated again (signed locally with the credentials of the
 * pod, IRSA or EKS Pod Identity: no call to AWS). Connections already open stay valid after their
 * token expires; the pool's maximum lifetime renews them.
 */
public final class RdsIamPasswordProvider implements DatabasePasswordProvider {

  /** Lifetime of an RDS authentication token. */
  public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

  private final RdsUtilities rds;
  private final String host;
  private final int port;
  private final String username;
  private final Clock clock;
  private final Duration refreshAfter;
  private final ReentrantLock lock = new ReentrantLock();
  private @Nullable String token;
  private Instant generatedAt = Instant.EPOCH;

  public RdsIamPasswordProvider(
      RdsUtilities rds,
      String host,
      int port,
      String username,
      Clock clock,
      Duration refreshAfter) {
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
    lock.lock();
    try {
      Instant now = clock.instant();
      String current = token;
      if (current == null || !now.isBefore(generatedAt.plus(refreshAfter))) {
        current =
            rds.generateAuthenticationToken(b -> b.hostname(host).port(port).username(username));
        token = current;
        generatedAt = now;
      }
      return current;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String toString() {
    return "RdsIamPasswordProvider[" + username + "@" + host + ":" + port + "]";
  }
}
