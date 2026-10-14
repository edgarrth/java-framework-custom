package pe.axiz.core.soap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;

/**
 * Minimal SOAP client over {@link HttpClient} with mandatory timeouts, WS-Security, fault mapping
 * and masked logging. For typed JAXB clients use the SOAP starter (Spring Web Services); both share
 * fault mapping and logging.
 */
public final class SoapClient {

  private static final Logger LOG = LoggerFactory.getLogger(SoapClient.class);

  private final String system;
  private final HttpClient http;
  private final URI endpoint;
  private final SoapVersion version;
  private final Duration timeout;
  private final SoapEnvelope.@Nullable Credentials credentials;
  private final ObjectMasker masker;
  private final Clock clock;

  public SoapClient(
      String system,
      HttpClient http,
      URI endpoint,
      SoapVersion version,
      Duration timeout,
      SoapEnvelope.@Nullable Credentials credentials,
      ObjectMasker masker,
      Clock clock) {
    this.system = Objects.requireNonNull(system);
    this.http = Objects.requireNonNull(http);
    this.endpoint = Objects.requireNonNull(endpoint);
    this.version = Objects.requireNonNull(version);
    this.timeout = Objects.requireNonNull(timeout);
    this.credentials = credentials;
    this.masker = Objects.requireNonNull(masker);
    this.clock = Objects.requireNonNull(clock);
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
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(envelope));
    if (version == SoapVersion.SOAP_11) {
      request
          .header("Content-Type", version.contentType())
          .header("SOAPAction", "\"" + soapAction + "\"");
    } else {
      request.header("Content-Type", version.contentType() + "; action=\"" + soapAction + "\"");
    }
    long start = System.nanoTime();
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "soap system={} action={} status={} durationMs={} response={}",
            system,
            soapAction,
            response.statusCode(),
            (System.nanoTime() - start) / 1_000_000,
            masker.maskByKey("body", maskXml(response.body()), OutputChannel.LOG));
      }
      return SoapResponse.payload(system, response.statusCode(), response.body());
    } catch (HttpTimeoutException e) {
      throw new ExternalServiceException(
          CoreErrors.EXTERNAL_TIMEOUT,
          new ExternalErrorDetail(system, 504, null, "timeout", ""),
          e);
    } catch (IOException e) {
      throw ExternalServiceException.from(
          new ExternalErrorDetail(system, -1, null, e.getClass().getSimpleName(), ""));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SoapException("Interrupted SOAP call to " + system, e);
    }
  }

  /** Masks the text of sensitive-looking elements (password, pan, cvv, token...). */
  static String maskXml(String xml) {
    return xml.replaceAll(
        "(?i)(<(?:[a-z0-9]+:)?(password|pin|cvv|pan|cardnumber|token|secret)[^>]*>)[^<]*(</)",
        "$1****$3");
  }
}
