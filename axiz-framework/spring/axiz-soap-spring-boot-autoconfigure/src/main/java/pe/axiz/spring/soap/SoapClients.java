package pe.axiz.spring.soap;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import pe.axiz.adapter.http.jdk.JdkSoapTransport;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.soap.SoapClient;
import pe.axiz.core.soap.SoapEnvelope;
import pe.axiz.core.soap.WsSecurity;

/**
 * Named SOAP clients. Each call is a CLIENT observation ({@code axiz.soap.client}) with the logical
 * system and SOAP action. Payloads are excluded from diagnostics. Closing the factory closes all
 * its owned transports and prevents new requests.
 */
public class SoapClients implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SoapClients.class);

  private final AxizSoapProperties properties;
  private final @Nullable SecretResolver secrets;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObservationRegistry is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final ObservationRegistry observations;

  private final Clock clock;
  private final TransportSecurityPolicy transportSecurity;
  private final Map<String, SoapClient> clients = new HashMap<>();
  private final ArrayList<HttpClient> transports = new ArrayList<>();
  private boolean closed;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "SOAP factory extension point validates endpoint policy before creating clients; "
              + "constructor acquires no external resources.")
  public SoapClients(
      AxizSoapProperties properties,
      @Nullable SecretResolver secrets,
      ObservationRegistry observations,
      Clock clock,
      TransportSecurityPolicy transportSecurity) {
    this.properties = Objects.requireNonNull(properties);
    this.secrets = secrets;
    this.observations = Objects.requireNonNull(observations);
    this.clock = Objects.requireNonNull(clock);
    this.transportSecurity = Objects.requireNonNull(transportSecurity);
    properties
        .clients()
        .forEach(
            (name, cfg) ->
                transportSecurity.validate(
                    cfg.endpoint(), "axiz.soap.clients." + name + ".endpoint"));
  }

  /** Calls an operation of a configured client and returns the response payload element. */
  public Element call(String client, String soapAction, String payloadXml) {
    SoapClient c = client(client);
    return Observation.createNotStarted(AxizObservations.SOAP_CLIENT, observations)
        .contextualName("soap " + client)
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.CLIENT, client))
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.OPERATION, soapAction))
        .observe(() -> c.call(soapAction, payloadXml));
  }

  private synchronized SoapClient client(String name) {
    if (closed) throw new IllegalStateException("SOAP clients are closed");
    return clients.computeIfAbsent(name, this::create);
  }

  @Override
  public void close() {
    ArrayList<HttpClient> owned;
    synchronized (this) {
      if (closed) return;
      closed = true;
      owned = new ArrayList<>(transports);
      transports.clear();
      clients.clear();
    }
    RuntimeException failure = null;
    for (var http : owned) {
      try {
        http.close();
      } catch (RuntimeException cleanup) {
        if (failure == null) failure = cleanup;
        else failure.addSuppressed(cleanup);
      }
    }
    if (failure != null) throw failure;
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
      try (var secret = s.resolve(ref).orElseThrow(() -> new SecretNotFoundException(ref))) {
        char[] password = secret.revealChars();
        try {
          credentials = new SoapEnvelope.Credentials(cfg.username(), password, mode);
        } finally {
          Arrays.fill(password, '\0');
        }
      }
    }
    HttpClient http = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build();
    try {
      SoapClient client =
          SoapClient.builder()
              .system(name)
              .transport(new JdkSoapTransport(http))
              .endpoint(cfg.endpoint())
              .version(cfg.version())
              .timeout(cfg.timeout())
              .credentials(credentials)
              .clock(clock)
              .transportSecurity(transportSecurity)
              .build();
      transports.add(http);
      return client;
    } catch (RuntimeException | Error failure) {
      try {
        http.close();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }
}
