package pe.axiz.core.soap;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;

/** SOAP protocol orchestration with explicit transport, WS-Security and safe fault mapping. */
public final class SoapClient {

  private static final Logger LOG = LoggerFactory.getLogger(SoapClient.class);

  private final String system;
  private final SoapTransport transport;
  private final URI endpoint;
  private final SoapVersion version;
  private final Duration timeout;
  private final SoapEnvelope.@Nullable Credentials credentials;
  private final Clock clock;

  private SoapClient(Builder builder) {
    String system = Objects.requireNonNull(builder.system, "system");
    SoapTransport transport = Objects.requireNonNull(builder.transport, "transport");
    URI endpoint = Objects.requireNonNull(builder.endpoint, "endpoint");
    SoapVersion version = Objects.requireNonNull(builder.version, "version");
    Duration timeout = Objects.requireNonNull(builder.timeout, "timeout");
    SoapEnvelope.@Nullable Credentials credentials = builder.credentials;
    Clock clock = Objects.requireNonNull(builder.clock, "clock");
    TransportSecurityPolicy transportSecurity =
        Objects.requireNonNull(builder.transportSecurity, "transportSecurity");
    this.system = system;
    this.transport = transport;
    this.endpoint = endpoint;
    Objects.requireNonNull(transportSecurity).validate(endpoint, "SOAP endpoint");
    this.version = version;
    this.timeout = timeout;
    if (!timeout.isPositive()) throw new IllegalArgumentException("SOAP timeout must be positive");
    if (endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getFragment() != null)
      throw new IllegalArgumentException("Invalid SOAP endpoint");
    this.credentials = credentials;
    this.clock = clock;
  }

  /**
   * Invokes an operation.
   *
   * @param soapAction SOAP action URI
   * @param payloadXml request payload
   * @return response payload element
   */
  public Element call(String soapAction, String payloadXml) {
    String envelope = SoapEnvelope.wrap(version, payloadXml, credentials, clock.instant());
    long start = System.nanoTime();
    try {
      SoapTransport.Reply response =
          transport.send(endpoint, version, soapAction, envelope, timeout);
      LOG.debug(
          "soap system={} status={} durationMs={}",
          system,
          response.status(),
          (System.nanoTime() - start) / 1_000_000);
      return SoapResponse.payload(system, response.status(), response.body());
    } catch (IOException e) {
      // no answer: a timeout (HttpTimeoutException) is 504, any other transport failure 503
      throw ExternalServiceException.from(
          new ExternalErrorDetail(system, -1, e.getClass().getSimpleName(), null, ""), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SoapException("Interrupted SOAP call to " + system, e);
    }
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable String system = null;
    private @Nullable SoapTransport transport = null;
    private @Nullable URI endpoint = null;
    private @Nullable SoapVersion version = null;
    private @Nullable Duration timeout = null;
    private SoapEnvelope.@Nullable Credentials credentials = null;
    private @Nullable Clock clock = null;
    private @Nullable TransportSecurityPolicy transportSecurity = null;

    private Builder() {}

    public Builder system(String value) {
      this.system = value;
      return this;
    }

    public Builder transport(SoapTransport value) {
      this.transport = value;
      return this;
    }

    public Builder endpoint(URI value) {
      this.endpoint = value;
      return this;
    }

    public Builder version(SoapVersion value) {
      this.version = value;
      return this;
    }

    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    public Builder credentials(SoapEnvelope.@Nullable Credentials value) {
      this.credentials = value;
      return this;
    }

    public Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    public Builder transportSecurity(TransportSecurityPolicy value) {
      this.transportSecurity = value;
      return this;
    }

    public SoapClient build() {
      return new SoapClient(this);
    }
  }
}
