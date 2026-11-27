package pe.axiz.core.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;

class SoapOrchestrationTest {
  @Test
  void secureConstructionRejectsPlaintextTransportBeforeAnyCredentialCanBeSent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SoapClient.builder()
                .system("bank")
                .transport(
                    (uri, version, action, envelope, timeout) -> {
                      throw new AssertionError("Transport must never be invoked");
                    })
                .endpoint(URI.create("http://bank.example.test/soap"))
                .version(SoapVersion.SOAP_11)
                .timeout(Duration.ofSeconds(2))
                .clock(Clock.systemUTC())
                .transportSecurity(TransportSecurityPolicy.secure())
                .build());
    assertEquals("Reply[status=502]", new SoapTransport.Reply(502, "private XML").toString());
    assertEquals("text/xml; charset=utf-8", SoapVersion.SOAP_11.contentType());
    assertEquals("application/soap+xml; charset=utf-8", SoapVersion.SOAP_12.contentType());
  }

  private SoapClient client(SoapTransport transport) {
    return SoapClient.builder()
        .system("bank")
        .transport(transport)
        .endpoint(URI.create("https://example.test/soap"))
        .version(SoapVersion.SOAP_11)
        .timeout(Duration.ofSeconds(2))
        .credentials(null)
        .clock(Clock.systemUTC())
        .transportSecurity(TransportSecurityPolicy.secure())
        .build();
  }

  @Test
  void protocolOrchestrationNeedsOnlyTheTransportPort() {
    var sent = new AtomicReference<String>();
    var client =
        client(
            (uri, version, action, envelope, timeout) -> {
              assertEquals("urn:debit", action);
              assertEquals(Duration.ofSeconds(2), timeout);
              sent.set(envelope);
              return new SoapTransport.Reply(
                  200,
                  "<s:Envelope"
                      + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><Ok/></s:Body></s:Envelope>");
            });
    assertEquals("Ok", client.call("urn:debit", "<Debit/>").getLocalName());
    assertTrue(sent.get().contains("Debit"));
    assertFalse(new SoapTransport.Reply(200, "private payload").toString().contains("private"));
  }

  @Test
  void mapsTimeoutsAndPreservesThreadInterruption() {
    var timeout =
        client(
            (uri, version, action, envelope, duration) -> {
              throw new HttpTimeoutException("private transport detail");
            });
    assertEquals(
        504,
        assertThrows(ExternalServiceException.class, () -> timeout.call("a", "<x/>"))
            .category()
            .httpStatus());
    var interrupted =
        client(
            (uri, version, action, envelope, duration) -> {
              throw new InterruptedException();
            });
    try {
      assertThrows(SoapException.class, () -> interrupted.call("a", "<x/>"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
