package pe.axiz.core.commons.net;

import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Environments;

/**
 * Política de TLS y de verificación del par para almacenes de datos, independiente de la tecnología
 * de su cliente.
 */
public record DataTransportSecurityPolicy(
    @Nullable String environment, boolean allowInsecureDataStores) {

  /** Lee la misma política de entorno explícita que usan todas las excepciones del framework. */
  public static DataTransportSecurityPolicy from(Function<String, @Nullable String> properties) {
    Objects.requireNonNull(properties, "properties");
    String setting = "axiz.transport.allow-insecure-data-stores";
    String raw = properties.apply(setting);
    if (raw != null && !"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
      throw new IllegalArgumentException(setting + " must be true or false");
    }
    return new DataTransportSecurityPolicy(
        properties.apply("axiz.config.environment"), Boolean.parseBoolean(raw));
  }

  /**
   * Exige cifrado y pares autenticados; nunca registra en log una cadena de conexión ni una
   * credencial.
   */
  public void requireVerified(boolean encrypted, boolean verifiedPeer, String integration) {
    if (encrypted && verifiedPeer) return;
    if (allowInsecureDataStores && Environments.allowsInMemoryState(environment)) return;
    throw new IllegalStateException(
        integration
            + " requires TLS with certificate and hostname verification; "
            + "insecure data stores require explicit local/test/ci environment and "
            + "axiz.transport.allow-insecure-data-stores=true");
  }
}
