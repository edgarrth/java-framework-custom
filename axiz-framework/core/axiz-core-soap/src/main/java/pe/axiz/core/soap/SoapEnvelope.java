package pe.axiz.core.soap;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Builds SOAP envelopes around a payload XML fragment. */
public final class SoapEnvelope {

  private SoapEnvelope() {}

  /**
   * Wraps the payload in an envelope.
   *
   * @param version SOAP version
   * @param payloadXml body content (one element)
   * @param security optional WS-Security credentials
   * @param now timestamp for WS-Security
   * @return the envelope XML
   */
  public static String wrap(
      SoapVersion version, String payloadXml, @Nullable Credentials security, Instant now) {
    Objects.requireNonNull(payloadXml);
    Document doc = SafeXml.newDocument();
    Element envelope = doc.createElementNS(version.namespace(), "soap:Envelope");
    doc.appendChild(envelope);
    Element header = doc.createElementNS(version.namespace(), "soap:Header");
    if (security != null) {
      header.appendChild(
          WsSecurity.usernameToken(
              doc, security.username(), security.password(), security.mode(), now));
      envelope.appendChild(header);
    }
    Element body = doc.createElementNS(version.namespace(), "soap:Body");
    envelope.appendChild(body);
    Node payload = doc.importNode(SafeXml.parse(payloadXml).getDocumentElement(), true);
    body.appendChild(payload);
    return SafeXml.toString(doc);
  }

  /**
   * WS-Security credentials.
   *
   * @param username user name
   * @param password password
   * @param mode text or digest
   */
  public record Credentials(String username, char[] password, WsSecurity.Mode mode) {
    @Override
    public String toString() {
      return "Credentials[username=" + username + ", password=****]";
    }
  }
}
