package pe.axiz.spring.soap;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.soap.SoapVersion;
import pe.axiz.core.soap.WsSecurity;

/**
 * Clientes SOAP para sistemas externos ({@code axiz.soap.clients.<name>}).
 *
 * @param clients clientes por nombre lógico
 */
@ConfigurationProperties("axiz.soap")
public record AxizSoapProperties(@DefaultValue Map<String, Client> clients) {
  public AxizSoapProperties {
    clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
  }

  /**
   * Un cliente.
   *
   * @param endpoint URL del servicio
   * @param version versión de SOAP
   * @param connectTimeout timeout de conexión
   * @param timeout timeout de la solicitud
   * @param username usuario de WS-Security, si el servicio lo necesita
   * @param passwordRef nombre del secreto de la contraseña de WS-Security
   * @param passwordMode {@code TEXT} o {@code LEGACY_SHA1_DIGEST}; obligatorio con un username (sin
   *     valor por defecto: decide la contraparte, y el digest SHA-1 nunca se elige en silencio)
   * @param sslBundle bundle SSL de Spring opcional para la confianza y el certificado de cliente de
   *     este cliente
   */
  public record Client(
      URI endpoint,
      @DefaultValue("SOAP_11") SoapVersion version,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("10s") Duration timeout,
      @Nullable String username,
      @Nullable String passwordRef,
      WsSecurity.@Nullable Mode passwordMode,
      @Nullable String sslBundle) {
    @ConstructorBinding
    public Client {}

    public Client(
        URI endpoint,
        SoapVersion version,
        Duration connectTimeout,
        Duration timeout,
        @Nullable String username,
        @Nullable String passwordRef,
        WsSecurity.@Nullable Mode passwordMode) {
      this(endpoint, version, connectTimeout, timeout, username, passwordRef, passwordMode, null);
    }

    /**
     * Construcción con nombre para los consumidores que configuran clientes de forma programática.
     */
    public static Builder builder(URI endpoint) {
      return new Builder(endpoint);
    }

    /**
     * Configuración mutable con nombre; cada build devuelve un valor de cliente inmutable e
     * independiente.
     */
    public static final class Builder {
      private final URI endpoint;
      private SoapVersion version = SoapVersion.SOAP_11;
      private Duration connectTimeout = Duration.ofSeconds(2);
      private Duration timeout = Duration.ofSeconds(10);
      private @Nullable String username;
      private @Nullable String passwordRef;
      private WsSecurity.@Nullable Mode passwordMode;
      private @Nullable String sslBundle;

      private Builder(URI endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint);
      }

      public Builder version(SoapVersion value) {
        version = Objects.requireNonNull(value);
        return this;
      }

      public Builder connectTimeout(Duration value) {
        connectTimeout = Objects.requireNonNull(value);
        return this;
      }

      public Builder timeout(Duration value) {
        timeout = Objects.requireNonNull(value);
        return this;
      }

      public Builder username(@Nullable String value) {
        username = value;
        return this;
      }

      public Builder passwordRef(@Nullable String value) {
        passwordRef = value;
        return this;
      }

      public Builder passwordMode(WsSecurity.@Nullable Mode value) {
        passwordMode = value;
        return this;
      }

      public Builder sslBundle(@Nullable String value) {
        sslBundle = value;
        return this;
      }

      public Client build() {
        return new Client(
            endpoint,
            version,
            connectTimeout,
            timeout,
            username,
            passwordRef,
            passwordMode,
            sslBundle);
      }
    }
  }
}
