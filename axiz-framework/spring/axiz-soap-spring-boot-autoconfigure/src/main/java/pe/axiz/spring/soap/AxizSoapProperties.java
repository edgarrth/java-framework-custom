package pe.axiz.spring.soap;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
   */
  public record Client(
      URI endpoint,
      @DefaultValue("SOAP_11") SoapVersion version,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("10s") Duration timeout,
      @Nullable String username,
      @Nullable String passwordRef,
      WsSecurity.@Nullable Mode passwordMode) {}
}
