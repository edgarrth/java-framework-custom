package pe.axiz.core.soap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** What goes on the wire: HTTP headers per SOAP version, envelope and WS-Security token. */
class SoapProtocolTest {

  private static final String SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/";
  private static final String SOAP12 = "http://www.w3.org/2003/05/soap-envelope";
  private static final Instant NOW = Instant.parse("2026-10-24T10:15:30.987Z");
  private static final String OK =
      "<s:Envelope xmlns:s=\""
          + SOAP11
          + "\"><s:Body><r:Ok xmlns:r=\"urn:r\"/></s:Body></s:Envelope>";

  @Test
  void anEnvelopeWithoutCredentialsHasNoHeader() {
    String xml = SoapEnvelope.wrap(SoapVersion.SOAP_12, "<p:Ping xmlns:p=\"urn:p\"/>", null, NOW);
    assertFalse(xml.startsWith("<?xml"), "no XML declaration inside the HTTP body");
    Element envelope = SafeXml.parse(xml).getDocumentElement();
    assertEquals("Envelope", envelope.getLocalName());
    assertEquals(SOAP12, envelope.getNamespaceURI());
    assertEquals(0, envelope.getElementsByTagNameNS(SOAP12, "Header").getLength());
    Element body = (Element) envelope.getElementsByTagNameNS(SOAP12, "Body").item(0);
    assertEquals("Ping", ((Element) body.getFirstChild()).getLocalName());
  }

  @Test
  void aTextTokenCarriesTheUserAndThePasswordOnly() {
    Document doc =
        SafeXml.parse(
            SoapEnvelope.wrap(
                SoapVersion.SOAP_11,
                "<x/>",
                new SoapEnvelope.Credentials("ana", "s3cret".toCharArray(), WsSecurity.Mode.TEXT),
                NOW));
    Element header = (Element) doc.getElementsByTagNameNS(SOAP11, "Header").item(0);
    Element security = (Element) header.getFirstChild();
    assertEquals(WsSecurity.WSSE, security.getNamespaceURI());
    assertEquals("Security", security.getLocalName());
    assertEquals(WsSecurity.WSU, security.getAttribute("xmlns:wsu"));
    assertEquals("ana", one(doc, WsSecurity.WSSE, "Username").getTextContent());
    Element password = one(doc, WsSecurity.WSSE, "Password");
    assertEquals("s3cret", password.getTextContent());
    assertTrue(password.getAttribute("Type").endsWith("#PasswordText"));
    assertEquals(0, doc.getElementsByTagNameNS(WsSecurity.WSSE, "Nonce").getLength());
    assertEquals(0, doc.getElementsByTagNameNS(WsSecurity.WSU, "Created").getLength());
  }

  @Test
  void digestPreservesUnicodeAndTheCallersPasswordArray() throws Exception {
    byte[] nonce = {1, 2, 3};
    String created = "2026-11-09T00:00:00Z";
    for (String text : new String[] {"", "páss\uD83D\uDD10漢字", "bad\uD800"}) {
      char[] password = text.toCharArray();
      // SOAP WS-Security SHA-1 digest interoperability fixture; production requires explicit
      // LEGACY_SHA1_DIGEST mode.
      // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
      var reference = MessageDigest.getInstance("SHA-1");
      reference.update(nonce);
      reference.update(created.getBytes(StandardCharsets.UTF_8));
      reference.update(text.getBytes(StandardCharsets.UTF_8));
      assertEquals(
          Base64.getEncoder().encodeToString(reference.digest()),
          WsSecurity.digest(nonce, created, password));
      assertArrayEquals(text.toCharArray(), password);
    }
  }

  @Test
  void aDigestTokenFollowsTheUsernameTokenProfile() throws Exception {
    SoapEnvelope.Credentials credentials =
        new SoapEnvelope.Credentials(
            "ana", "s3cret".toCharArray(), WsSecurity.Mode.LEGACY_SHA1_DIGEST);
    Document doc = SafeXml.parse(SoapEnvelope.wrap(SoapVersion.SOAP_11, "<x/>", credentials, NOW));
    Element password = one(doc, WsSecurity.WSSE, "Password");
    assertTrue(password.getAttribute("Type").endsWith("#PasswordDigest"));
    Element nonce = one(doc, WsSecurity.WSSE, "Nonce");
    assertTrue(nonce.getAttribute("EncodingType").endsWith("#Base64Binary"));
    byte[] nonceBytes = Base64.getDecoder().decode(nonce.getTextContent());
    assertEquals(16, nonceBytes.length);
    String created = one(doc, WsSecurity.WSU, "Created").getTextContent();
    assertEquals("2026-10-24T10:15:30Z", created, "whole seconds");

    // SOAP WS-Security SHA-1 digest interoperability fixture; production requires explicit
    // LEGACY_SHA1_DIGEST mode.
    // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    sha1.update(nonceBytes);
    sha1.update(created.getBytes(StandardCharsets.UTF_8));
    sha1.update("s3cret".getBytes(StandardCharsets.UTF_8));
    assertEquals(Base64.getEncoder().encodeToString(sha1.digest()), password.getTextContent());
    assertFalse(password.getTextContent().contains("s3cret"));

    String other = SoapEnvelope.wrap(SoapVersion.SOAP_11, "<x/>", credentials, NOW);
    assertNotEquals(
        nonce.getTextContent(),
        one(SafeXml.parse(other), WsSecurity.WSSE, "Nonce").getTextContent(),
        "a new nonce for every message");
    assertArrayEquals("s3cret".toCharArray(), credentials.password(), "credentials untouched");
  }

  private static Element one(Document doc, String namespace, String localName) {
    NodeList found = doc.getElementsByTagNameNS(namespace, localName);
    assertEquals(1, found.getLength(), localName);
    return (Element) found.item(0);
  }
}
