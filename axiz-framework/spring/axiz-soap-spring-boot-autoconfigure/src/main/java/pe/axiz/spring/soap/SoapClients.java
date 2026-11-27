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
import javax.net.ssl.SSLParameters;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.SslBundles;
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
 * Clientes SOAP con nombre. Cada llamada es una observación CLIENT ({@code axiz.soap.client}) con
 * el sistema lógico y la acción SOAP. Los payloads se excluyen del diagnóstico. Cerrar la fábrica
 * cierra todos sus transportes propios e impide solicitudes nuevas.
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SSL bundle registry is an injected collaborator; copying it would lose managed identity and updates.")
  private final @Nullable SslBundles sslBundles;

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
    this(properties, secrets, observations, clock, transportSecurity, null);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Validates endpoint policy before allocating any client or transport; "
              + "partially constructed instances own no external resource.")
  public SoapClients(
      AxizSoapProperties properties,
      @Nullable SecretResolver secrets,
      ObservationRegistry observations,
      Clock clock,
      TransportSecurityPolicy transportSecurity,
      @Nullable SslBundles sslBundles) {
    this.sslBundles = sslBundles;
    this.properties = Objects.requireNonNull(properties);
    this.secrets = secrets;
    this.observations = Objects.requireNonNull(observations);
    this.clock = Objects.requireNonNull(clock);
    this.transportSecurity = Objects.requireNonNull(transportSecurity);
    properties
        .clients()
        .forEach(
            (name, cfg) -> {
              transportSecurity.validate(cfg.endpoint(), "axiz.soap.clients." + name + ".endpoint");
              validateCredentials(name, cfg);
            });
  }

  /**
   * Invoca una operación de un cliente configurado y devuelve el elemento payload de la respuesta.
   */
  public Element call(String client, String soapAction, String payloadXml) {
    SoapClient c = client(client);
    return Observation.createNotStarted(AxizObservations.SOAP_CLIENT, observations)
        .contextualName("soap " + client)
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.CLIENT, client))
        .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.OPERATION, soapAction))
        .observe(
            () -> {
              try (var credentials = credentials(configuration(client))) {
                return c.call(soapAction, payloadXml, credentials);
              }
            });
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
    AxizSoapProperties.Client cfg = configuration(name);
    return createTransport(name, cfg);
  }

  private AxizSoapProperties.Client configuration(String name) {
    AxizSoapProperties.Client cfg = properties.clients().get(name);
    if (cfg == null) {
      throw new IllegalArgumentException(
          "SOAP client " + name + " is not configured under axiz.soap.clients");
    }
    return cfg;
  }

  private SoapClient createTransport(String name, AxizSoapProperties.Client cfg) {
    if (cfg.passwordMode() == WsSecurity.Mode.LEGACY_SHA1_DIGEST) {
      LOG.warn(
          "SOAP client {} uses the legacy WS-Security SHA-1 password digest required by its"
              + " counterparty; keep it over TLS and plan a modern profile (mutual TLS, X.509)",
          name);
    }
    var builder = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout());
    if (cfg.sslBundle() != null) {
      if (sslBundles == null)
        throw new IllegalStateException("SOAP ssl-bundle requires configured SSL bundles");
      var bundle = sslBundles.getBundle(cfg.sslBundle());
      builder.sslContext(bundle.createSslContext());
      var options = new SSLParameters();
      options.setEndpointIdentificationAlgorithm("HTTPS");
      var protocols = bundle.getOptions().getEnabledProtocols();
      if (protocols != null) options.setProtocols(protocols);
      var ciphers = bundle.getOptions().getCiphers();
      if (ciphers != null) options.setCipherSuites(ciphers);
      builder.sslParameters(options);
    }
    HttpClient http = builder.build();
    try {
      SoapClient client =
          SoapClient.builder()
              .system(name)
              .transport(new JdkSoapTransport(http))
              .endpoint(cfg.endpoint())
              .version(cfg.version())
              .timeout(cfg.timeout())
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

  private void validateCredentials(String name, AxizSoapProperties.Client cfg) {
    if (cfg.username() != null) {
      String prefix = "axiz.soap.clients." + name + ".";
      WsSecurity.Mode mode = cfg.passwordMode();
      if (mode == null) {
        throw new IllegalStateException(
            prefix
                + "password-mode is required with a username: TEXT (over TLS) or"
                + " LEGACY_SHA1_DIGEST (only when the counterparty demands it)");
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
    } else if (cfg.passwordRef() != null || cfg.passwordMode() != null) {
      throw new IllegalStateException(
          "axiz.soap.clients." + name + ".username is required with credentials");
    }
  }

  private SoapEnvelope.@Nullable Credentials credentials(AxizSoapProperties.Client cfg) {
    if (cfg.username() != null) {
      WsSecurity.Mode mode = Objects.requireNonNull(cfg.passwordMode());
      String ref = Objects.requireNonNull(cfg.passwordRef());
      SecretResolver s = Objects.requireNonNull(secrets);
      try (var secret = s.resolve(ref).orElseThrow(() -> new SecretNotFoundException(ref))) {
        char[] password = secret.revealChars();
        try {
          return new SoapEnvelope.Credentials(cfg.username(), password, mode);
        } finally {
          Arrays.fill(password, '\0');
        }
      }
    }
    return null;
  }
}
