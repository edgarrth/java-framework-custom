package pe.axiz.adapter.http.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.soap.SoapClient;
import pe.axiz.core.soap.SoapException;
import pe.axiz.core.soap.SoapVersion;

/** What goes on the wire: HTTP headers per SOAP version, envelope and WS-Security token. */
class SoapTransportTest {

  private static final String SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/";
  private static final String SOAP12 = "http://www.w3.org/2003/05/soap-envelope";
  private static final Instant NOW = Instant.parse("2026-10-24T10:15:30.987Z");
  private static final String OK =
      "<s:Envelope xmlns:s=\""
          + SOAP11
          + "\"><s:Body><r:Ok xmlns:r=\"urn:r\"/></s:Body></s:Envelope>";

  private HttpServer server;
  private final AtomicReference<String> contentType = new AtomicReference<>();
  private final AtomicReference<String> soapAction = new AtomicReference<>();
  private volatile long delayMs;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ws",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          soapAction.set(exchange.getRequestHeaders().getFirst("SOAPAction"));
          try {
            // Deliberately stall the HTTP peer to exercise the real transport timeout.
            Thread.sleep(delayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = OK.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private SoapClient client(SoapVersion version) {
    return SoapClient.builder()
        .system("core")
        .transport(new JdkSoapTransport(HttpClient.newHttpClient()))
        .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ws"))
        .version(version)
        .timeout(Duration.ofSeconds(2))
        .credentials(null)
        .clock(Clock.fixed(NOW, ZoneOffset.UTC))
        .transportSecurity(new TransportSecurityPolicy("test", true))
        .build();
  }

  @Test
  void soap11SendsTheActionInItsHeaderAndSoap12InTheContentType() {
    assertEquals("Ok", client(SoapVersion.SOAP_11).call("urn:core:debit", "<x/>").getLocalName());
    assertEquals("text/xml; charset=utf-8", contentType.get());
    assertEquals("\"urn:core:debit\"", soapAction.get());

    client(SoapVersion.SOAP_12).call("urn:core:debit", "<x/>");
    assertEquals(
        "application/soap+xml; charset=utf-8; action=\"urn:core:debit\"", contentType.get());
    assertNull(soapAction.get(), "SOAP 1.2 has no SOAPAction header");
  }

  @Test
  void aCallWithoutAnswerIsATransportFailure() {
    SoapClient client = client(SoapVersion.SOAP_11);
    server.stop(0);
    ExternalServiceException e =
        assertThrows(ExternalServiceException.class, () -> client.call("a", "<x/>"));
    assertEquals(503, e.category().httpStatus());
    assertEquals(-1, e.detail().status());
    assertEquals("core", e.detail().system());
    assertEquals("ConnectException", e.detail().code());
  }

  @Test
  void anInterruptedCallKeepsTheInterruptFlag() {
    delayMs = 1_000;
    SoapClient client = client(SoapVersion.SOAP_11);
    Thread.currentThread().interrupt();
    try {
      SoapException e = assertThrows(SoapException.class, () -> client.call("a", "<x/>"));
      assertEquals("Interrupted SOAP call to core", e.getMessage());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
