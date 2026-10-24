package pe.axiz.spring.soap;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.soap.SoapClient;
import pe.axiz.core.soap.SoapEnvelope;
import pe.axiz.core.soap.WsSecurity;

/**
 * Named SOAP clients. Each call is a CLIENT observation ({@code axiz.soap.client}) with the logical
 * system and SOAP action; payloads are logged masked by the core client.
 */
public class SoapClients {

  private static final Logger LOG = LoggerFactory.getLogger(SoapClients.class);

  private final AxizSoapProperties properties;
  private final @Nullable SecretResolver secrets;
  private final ObjectMasker masker;
  private final ObservationRegistry observations;
  private final Clock clock;
  private final Map<String, SoapClient> clients = new ConcurrentHashMap<>();

  public SoapClients(
      AxizSoapProperties properties,
      @Nullable SecretResolver secrets,
      ObjectMasker masker,
      ObservationRegistry observations,
      Clock clock) {
    this.properties = Objects.requireNonNull(properties);
    this.secrets = secrets;
    this.masker = Objects.requireNonNull(masker);
    this.observations = Objects.requireNonNull(observations);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Calls an operation of a configured client and returns the response payload element. */
  public Element call(String client, String soapAction, String payloadXml) {
    SoapClient c = clients.computeIfAbsent(client, this::create);
    return Observation.createNotStarted(AxizObservations.SOAP_CLIENT, observations)
        .contextualName("soap " + client)
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.CLIENT, client))
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.OPERATION, soapAction))
        .observe(() -> c.call(soapAction, payloadXml));
  }

  private SoapClient create(String name) {
    AxizSoapProperties.Client cfg = properties.clients().get(name);
    if (cfg == null) {
      throw new IllegalArgumentException(
          "SOAP client " + name + " is not configured under axiz.soap.clients");
    }
    SoapEnvelope.Credentials credentials = null;
    if (cfg.username() != null) {
      String prefix = "axiz.soap.clients." + name + ".";
      WsSecurity.Mode mode = cfg.passwordMode();
      if (mode == null) {
        throw new IllegalStateException(
            prefix
                + "password-mode is required with a username: TEXT (over TLS) or"
                + " LEGACY_SHA1_DIGEST (only when the counterparty demands it)");
      }
      if (!"https".equalsIgnoreCase(cfg.endpoint().getScheme()) && !cfg.allowPlainHttp()) {
        throw new IllegalStateException(
            prefix
                + "endpoint must be https:// to send WS-Security credentials ("
                + cfg.endpoint()
                + "); allow-plain-http is only for local stand-ins");
      }
      if (mode == WsSecurity.Mode.LEGACY_SHA1_DIGEST) {
        LOG.warn(
            "SOAP client {} uses the legacy WS-Security SHA-1 password digest required by its"
                + " counterparty; keep it over TLS and plan a modern profile (mutual TLS, X.509)",
            name);
      }
      String ref = cfg.passwordRef();
      if (ref == null || ref.isBlank()) {
        throw new IllegalStateException(prefix + "password-ref is required with a username");
      }
      SecretResolver s = secrets;
      if (s == null) {
        throw new IllegalStateException(
            prefix + "password-ref needs a SecretResolver: add axiz-secrets-spring-boot-starter");
      }
      char[] password =
          s.resolve(ref).orElseThrow(() -> new SecretNotFoundException(ref)).revealChars();
      credentials = new SoapEnvelope.Credentials(cfg.username(), password, mode);
      Arrays.fill(password, '\0');
    }
    HttpClient http = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build();
    return new SoapClient(
        name, http, cfg.endpoint(), cfg.version(), cfg.timeout(), credentials, masker, clock);
  }
}
