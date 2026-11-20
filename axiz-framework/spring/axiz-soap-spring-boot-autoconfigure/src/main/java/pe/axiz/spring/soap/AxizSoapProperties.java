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
 * SOAP clients for legacy systems ({@code axiz.soap.clients.<name>}).
 *
 * @param clients clients by logical name
 */
@ConfigurationProperties("axiz.soap")
public record AxizSoapProperties(@DefaultValue Map<String, Client> clients) {
  public AxizSoapProperties {
    clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
  }

  /**
   * One client.
   *
   * @param endpoint service URL
   * @param version SOAP version
   * @param connectTimeout connection timeout
   * @param timeout request timeout
   * @param username WS-Security user, if the service needs one
   * @param passwordRef secret name of the WS-Security password
   * @param passwordMode {@code TEXT} or {@code LEGACY_SHA1_DIGEST}; required with a username (no
   *     default: the counterparty decides, and the legacy digest is never chosen silently)
   * @param sslBundle optional Spring SSL bundle for this client's trust and client certificate
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

    /** Named construction for consumers that configure clients programmatically. */
    public static Builder builder(URI endpoint) {
      return new Builder(endpoint);
    }

    /** Mutable named configuration; each build returns an independent immutable client value. */
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
