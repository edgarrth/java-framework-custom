package pe.axiz.spring.soap;

import java.net.URI;
import java.time.Duration;
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

  /**
   * One client.
   *
   * @param endpoint service URL
   * @param version SOAP version
   * @param connectTimeout connection timeout
   * @param timeout request timeout
   * @param username WS-Security user, if the service needs one
   * @param passwordRef secret name of the WS-Security password
   * @param passwordMode {@code DIGEST} or {@code TEXT}
   */
  public record Client(
      URI endpoint,
      @DefaultValue("SOAP_11") SoapVersion version,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("10s") Duration timeout,
      @Nullable String username,
      @Nullable String passwordRef,
      @DefaultValue("DIGEST") WsSecurity.Mode passwordMode) {}
}
