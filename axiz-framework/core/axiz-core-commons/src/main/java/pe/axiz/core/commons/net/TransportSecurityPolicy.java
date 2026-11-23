package pe.axiz.core.commons.net;

import java.net.URI;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Environments;

/** Frontera de transporte inmutable. HTTP requiere una excepción explícita en local/test/ci. */
public record TransportSecurityPolicy(@Nullable String environment, boolean allowPlainHttp) {
  public static final String SETTING = "axiz.transport.allow-plain-http";

  public static TransportSecurityPolicy secure() {
    return new TransportSecurityPolicy(null, false);
  }

  /**
   * Configuración de proceso explícita para usuarios independientes, nunca una exención automática
   * de loopback.
   */
  public static TransportSecurityPolicy system() {
    return from(key -> null);
  }

  /**
   * Las propiedades de contexto tienen precedencia sobre la configuración explícita del proceso. No
   * se realiza DNS.
   */
  public static TransportSecurityPolicy from(Function<String, @Nullable String> properties) {
    Objects.requireNonNull(properties);
    String environment =
        first(
            properties.apply("axiz.config.environment"),
            System.getProperty("axiz.config.environment"),
            System.getenv("AXIZ_CONFIG_ENVIRONMENT"));
    String raw =
        first(
            properties.apply(SETTING),
            System.getProperty(SETTING),
            System.getenv("AXIZ_TRANSPORT_ALLOW_PLAIN_HTTP"));
    if (raw != null && !"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw))
      throw new IllegalArgumentException(SETTING + " must be true or false");
    return new TransportSecurityPolicy(environment, Boolean.parseBoolean(raw));
  }

  public void validate(URI endpoint, String setting) {
    Objects.requireNonNull(endpoint);
    Objects.requireNonNull(setting);
    if (!endpoint.isAbsolute()
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getFragment() != null)
      throw new IllegalArgumentException(
          setting + " requires an absolute HTTP(S) URI without userinfo or fragment");
    if ("https".equalsIgnoreCase(endpoint.getScheme())) return;
    if ("http".equalsIgnoreCase(endpoint.getScheme())
        && allowPlainHttp
        && Environments.allowsInMemoryState(environment)) return;
    throw new IllegalArgumentException(
        setting
            + " requires HTTPS; HTTP needs "
            + SETTING
            + "=true and axiz.config.environment=local/test/ci");
  }

  /** Un cliente lógico autenticado no puede enviar sus credenciales a otro origen. */
  public void validateTarget(URI base, URI target, boolean authenticated, String setting) {
    validate(target, setting);
    if (authenticated
        && (!base.getScheme().equalsIgnoreCase(target.getScheme())
            || !Objects.requireNonNull(base.getHost()).equalsIgnoreCase(target.getHost())
            || port(base) != port(target)))
      throw new IllegalArgumentException(setting + " cannot forward credentials to another origin");
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static @Nullable String first(@Nullable String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value.strip();
    return null;
  }
}
