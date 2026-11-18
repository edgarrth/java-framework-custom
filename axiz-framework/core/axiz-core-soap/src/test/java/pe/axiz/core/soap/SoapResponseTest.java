package pe.axiz.core.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import pe.axiz.core.errors.ExternalServiceException;

/** Reading SOAP answers without a server, and the credentials value type. */
class SoapResponseTest {
  @Test
  void qualifiedSoap11FaultsAndSoap12ReasonsRetainTheirCodesWithoutReturningAPayload() {
    String eleven =
        "<soap:Body><soap:Fault><soap:faultcode>soap:Server</soap:faultcode>"
            + "<soap:faultstring> failed </soap:faultstring></soap:Fault></soap:Body>";
    var failure =
        assertThrows(
            ExternalServiceException.class,
            () -> SoapResponse.payload("core", 200, envelope(eleven)));
    assertEquals("soap:Server", failure.detail().code());
    assertEquals("failed", failure.detail().message());
    String twelve =
        "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body><s:Fault>"
            + "<s:Code><s:Value>s:Receiver</s:Value></s:Code><s:Reason><s:Text> failed </s:Text></s:Reason>"
            + "</s:Fault></s:Body></s:Envelope>";
    var modern =
        assertThrows(
            ExternalServiceException.class, () -> SoapResponse.payload("core", 503, twelve));
    assertEquals("s:Receiver", modern.detail().code());
    assertEquals("failed", modern.detail().message());
    assertEquals(503, modern.detail().status());
  }

  private static final String SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/";

  private static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"" + SOAP11 + "\">" + body + "</soap:Envelope>";
  }

  @Test
  void thePayloadIsTheFirstElementOfTheBody() {
    Element payload =
        SoapResponse.payload(
            "core",
            200,
            envelope(
                "<soap:Header/><soap:Body>\n  <ns:BalanceResponse xmlns:ns=\"urn:bank\">"
                    + "<ns:amount>10.00</ns:amount></ns:BalanceResponse></soap:Body>"));
    assertEquals("BalanceResponse", payload.getLocalName());
  }

  @Test
  void anAnswerWithoutPayloadIsAnErrorOfTheDependency() {
    ExternalServiceException blank =
        assertThrows(ExternalServiceException.class, () -> SoapResponse.payload("core", 502, " "));
    assertEquals("empty SOAP response", blank.detail().message());
    ExternalServiceException noBody =
        assertThrows(
            ExternalServiceException.class,
            () -> SoapResponse.payload("core", 200, envelope("<soap:Header/>")));
    assertEquals("no SOAP Body", noBody.detail().message());
    ExternalServiceException emptyBody =
        assertThrows(
            ExternalServiceException.class,
            () -> SoapResponse.payload("core", 200, envelope("<soap:Body>  </soap:Body>")));
    assertEquals("empty SOAP Body", emptyBody.detail().message());
  }

  @Test
  void aFaultKeepsTheStatusOfTheAnswerOrBecomesA500() {
    String fault =
        envelope(
            "<soap:Body><soap:Fault><faultcode>soap:Client</faultcode>"
                + "<faultstring> Invalid account </faultstring></soap:Fault></soap:Body>");
    ExternalServiceException ok200 =
        assertThrows(
            ExternalServiceException.class, () -> SoapResponse.payload("core", 200, fault));
    assertEquals(500, ok200.detail().status(), "a fault answered with 200 is still a failure");
    assertEquals("soap:Client", ok200.detail().code());
    assertEquals("Invalid account", ok200.detail().message());
    ExternalServiceException with503 =
        assertThrows(
            ExternalServiceException.class, () -> SoapResponse.payload("core", 503, fault));
    assertEquals(503, with503.detail().status());

    ExternalServiceException bare =
        assertThrows(
            ExternalServiceException.class,
            () ->
                SoapResponse.payload(
                    "core", 500, envelope("<soap:Body><soap:Fault/></soap:Body>")));
    assertNull(bare.detail().code(), "a fault without code or reason");
  }

  @Test
  void credentialsCompareByContentAndNeverPrintThePassword() {
    SoapEnvelope.Credentials a =
        new SoapEnvelope.Credentials("ana", "s3cret".toCharArray(), WsSecurity.Mode.TEXT);
    SoapEnvelope.Credentials same =
        new SoapEnvelope.Credentials("ana", "s3cret".toCharArray(), WsSecurity.Mode.TEXT);
    assertEquals(a, same);
    assertEquals(a.hashCode(), same.hashCode());
    assertNotEquals(
        a, new SoapEnvelope.Credentials("luis", "s3cret".toCharArray(), WsSecurity.Mode.TEXT));
    assertNotEquals(
        a, new SoapEnvelope.Credentials("ana", "other".toCharArray(), WsSecurity.Mode.TEXT));
    assertNotEquals(
        a,
        new SoapEnvelope.Credentials(
            "ana", "s3cret".toCharArray(), WsSecurity.Mode.LEGACY_SHA1_DIGEST));
    assertFalse(a.equals("ana"));
    assertFalse(a.toString().contains("s3cret"));
    assertTrue(a.toString().contains("ana"));

    char[] copy = a.password();
    copy[0] = 'X';
    assertEquals('s', a.password()[0], "the stored password cannot be changed from outside");
    assertEquals("ana", a.username());
    assertEquals(WsSecurity.Mode.TEXT, a.mode());
  }
}
