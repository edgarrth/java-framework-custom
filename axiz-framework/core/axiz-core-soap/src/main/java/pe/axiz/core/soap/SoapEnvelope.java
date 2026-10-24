package pe.axiz.core.soap;

import java.time.Instant;
import java.util.Arrays;
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
      char[] password = security.password();
      try {
        header.appendChild(
            WsSecurity.usernameToken(doc, security.username(), password, security.mode(), now));
      } finally {
        Arrays.fill(password, '\0');
      }
      envelope.appendChild(header);
    }
    Element body = doc.createElementNS(version.namespace(), "soap:Body");
    envelope.appendChild(body);
    Node payload = doc.importNode(SafeXml.parse(payloadXml).getDocumentElement(), true);
    body.appendChild(payload);
    return SafeXml.toString(doc);
  }

  /**
   * WS-Security credentials. The password is copied on the way in and out, so the caller can clear
   * its own array; it never appears in {@link #toString()}.
   */
  public static final class Credentials {
    private final String username;
    private final char[] password;
    private final WsSecurity.Mode mode;

    /**
     * Creates the credentials.
     *
     * @param username user name
     * @param password password
     * @param mode text or digest
     */
    public Credentials(String username, char[] password, WsSecurity.Mode mode) {
      this.username = Objects.requireNonNull(username, "username");
      this.password = password.clone();
      this.mode = Objects.requireNonNull(mode, "mode");
    }

    public String username() {
      return username;
    }

    /** A copy of the password; clear it after use. */
    public char[] password() {
      return password.clone();
    }

    public WsSecurity.Mode mode() {
      return mode;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof Credentials c
          && username.equals(c.username)
          && mode == c.mode
          && Arrays.equals(password, c.password);
    }

    @Override
    public int hashCode() {
      return Objects.hash(username, mode);
    }

    @Override
    public String toString() {
      return "Credentials[username=" + username + ", password=****]";
    }
  }
}
