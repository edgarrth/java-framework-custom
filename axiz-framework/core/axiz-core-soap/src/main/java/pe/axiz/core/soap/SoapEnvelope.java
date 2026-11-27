package pe.axiz.core.soap;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Construye envelopes SOAP alrededor de un fragmento XML de payload. */
public final class SoapEnvelope {

  private SoapEnvelope() {}

  /**
   * Envuelve el payload en un envelope.
   *
   * @param version versión de SOAP
   * @param payloadXml contenido del cuerpo (un elemento)
   * @param security credenciales WS-Security opcionales
   * @param now marca de tiempo para WS-Security
   * @return el XML del envelope
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
   * Credenciales WS-Security. La contraseña se copia a la entrada y a la salida, de modo que quien
   * llama puede limpiar su propio array; nunca aparece en {@link #toString()}.
   */
  public static final class Credentials implements AutoCloseable {
    private final String username;
    private final char[] password;
    private final WsSecurity.Mode mode;
    private boolean closed;

    /**
     * Crea las credenciales.
     *
     * @param username nombre de usuario
     * @param password contraseña
     * @param mode texto o digest
     */
    public Credentials(String username, char[] password, WsSecurity.Mode mode) {
      this.username = Objects.requireNonNull(username, "username");
      this.password = password.clone();
      this.mode = Objects.requireNonNull(mode, "mode");
    }

    public String username() {
      return username;
    }

    /** Copia de la contraseña; debe limpiarse tras usarla. */
    public synchronized char[] password() {
      if (closed) throw new IllegalStateException("SOAP credentials are closed");
      return password.clone();
    }

    @Override
    public synchronized void close() {
      Arrays.fill(password, '\0');
      closed = true;
    }

    private synchronized char @Nullable [] snapshot() {
      return closed ? null : password.clone();
    }

    public WsSecurity.Mode mode() {
      return mode;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) return true;
      if (!(o instanceof Credentials c) || !username.equals(c.username) || mode != c.mode)
        return false;
      char[] left = snapshot();
      char[] right = c.snapshot();
      try {
        return left != null && right != null && Arrays.equals(left, right);
      } finally {
        if (left != null) Arrays.fill(left, '\0');
        if (right != null) Arrays.fill(right, '\0');
      }
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
