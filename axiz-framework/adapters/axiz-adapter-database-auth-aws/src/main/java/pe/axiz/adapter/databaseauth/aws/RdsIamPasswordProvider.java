package pe.axiz.adapter.databaseauth.aws;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * Tokens de autenticación IAM para Aurora PostgreSQL. Un token es válido durante 15 minutos; se
 * reutiliza hasta {@code refreshAfter} y luego se genera de nuevo (firmado localmente con las
 * credenciales del pod, IRSA o EKS Pod Identity: sin llamada a AWS). Las conexiones abiertas siguen
 * siendo válidas después de que su token expira; la vida máxima del pool las renueva.
 */
public final class RdsIamPasswordProvider implements DatabasePasswordProvider, AutoCloseable {

  /** Vida de un token de autenticación de RDS. */
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

  /**
   * Con propiedad del ciclo de vida del proveedor de credenciales precalentado que usa el firmante.
   */
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
