package pe.axiz.core.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.sensitive.ObjectMasker;

class SoapTest {

  private HttpServer server;
  private final AtomicReference<String> received = new AtomicReference<>();
  private final AtomicReference<String> action = new AtomicReference<>();
  private volatile String reply = "";
  private volatile int status = 200;
  private volatile long delayMs;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ws",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          action.set(exchange.getRequestHeaders().getFirst("SOAPAction"));
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = reply.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private SoapClient client(SoapVersion version, SoapEnvelope.Credentials creds, Duration timeout) {
    return new SoapClient(
        "core-legacy",
        HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ws"),
        version,
        timeout,
        creds,
        ObjectMasker.defaults(),
        Clock.systemUTC());
  }

  @Test
  void callReturnsPayloadAndSendsWsSecurityDigest() {
    reply =
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><r:DebitResponse"
            + " xmlns:r=\"urn:core\"><r:status>OK</r:status></r:DebitResponse></s:Body></s:Envelope>";
    SoapClient client =
        client(
            SoapVersion.SOAP_11,
            new SoapEnvelope.Credentials(
                "svc", "pw".toCharArray(), WsSecurity.Mode.LEGACY_SHA1_DIGEST),
            Duration.ofSeconds(2));
    Element payload =
        client.call(
            "urn:core:debit", "<c:Debit xmlns:c=\"urn:core\"><c:account>123</c:account></c:Debit>");
    assertEquals("DebitResponse", payload.getLocalName());
    assertEquals("OK", payload.getTextContent());
    assertEquals("\"urn:core:debit\"", action.get());
    assertTrue(received.get().contains("PasswordDigest"));
    assertFalse(received.get().contains(">pw<"));
    assertTrue(received.get().contains("<c:account>123</c:account>"));
  }

  @Test
  void faultsBecomeExternalErrors() {
    reply =
        "<soap:Envelope"
            + " xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
            + "<faultcode>soap:Server</faultcode><faultstring>Insufficient"
            + " funds</faultstring></soap:Fault></soap:Body></soap:Envelope>";
    status = 500;
    ExternalServiceException e =
        assertThrows(
            ExternalServiceException.class,
            () -> client(SoapVersion.SOAP_11, null, Duration.ofSeconds(2)).call("a", "<x/>"));
    assertEquals("soap:Server", e.detail().code());
    assertEquals("Insufficient funds", e.detail().message());
  }

  @Test
  void soap12FaultAndEmptyResponses() {
    reply =
        "<env:Envelope"
            + " xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\"><env:Body><env:Fault><env:Code>"
            + "<env:Value>env:Sender</env:Value></env:Code><env:Reason><env:Text>Bad</env:Text>"
            + "</env:Reason></env:Fault></env:Body></env:Envelope>";
    ExternalServiceException e =
        assertThrows(
            ExternalServiceException.class,
            () -> client(SoapVersion.SOAP_12, null, Duration.ofSeconds(2)).call("a", "<x/>"));
    assertEquals("env:Sender", e.detail().code());
    assertEquals(500, e.detail().status());
    reply = "";
    status = 503;
    ExternalServiceException empty =
        assertThrows(
            ExternalServiceException.class,
            () -> client(SoapVersion.SOAP_11, null, Duration.ofSeconds(2)).call("a", "<x/>"));
    assertEquals(503, empty.category().httpStatus());
  }

  @Test
  void timeoutIsGatewayTimeout() {
    delayMs = 500;
    ExternalServiceException e =
        assertThrows(
            ExternalServiceException.class,
            () -> client(SoapVersion.SOAP_11, null, Duration.ofMillis(100)).call("a", "<x/>"));
    assertEquals(504, e.category().httpStatus());
  }

  @Test
  void xxeIsRejectedAndSensitiveXmlMasked() {
    String xxe =
        "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
    assertThrows(SoapException.class, () -> SafeXml.parse(xxe));
    assertEquals(
        "<a><ns:Password>****</ns:Password><pan x=\"1\">****</pan></a>",
        SoapClient.maskXml("<a><ns:Password>p</ns:Password><pan x=\"1\">4111</pan></a>"));
    String text =
        SoapEnvelope.wrap(
            SoapVersion.SOAP_12,
            "<x/>",
            new SoapEnvelope.Credentials("u", "p".toCharArray(), WsSecurity.Mode.TEXT),
            Instant.EPOCH);
    assertTrue(text.contains("PasswordText"));
    assertFalse(
        new SoapEnvelope.Credentials("u", "p".toCharArray(), WsSecurity.Mode.TEXT)
            .toString()
            .contains("=p"));
  }
}
