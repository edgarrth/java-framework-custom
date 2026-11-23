package pe.axiz.core.soap;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Cabecera UsernameToken de WS-Security para contrapartes SOAP heredadas. Ambos modos son
 * autenticación heredada y solo se usan sobre TLS (el cliente rechaza HTTP plano salvo que se
 * permita explícitamente un sustituto local): {@link Mode#TEXT} envía la contraseña dentro del
 * canal TLS; {@link Mode#LEGACY_SHA1_DIGEST} envía {@code Base64(SHA-1(nonce + created +
 * password))} con nonce y marca de tiempo, porque UsernameToken Profile 1.0 fija SHA-1 para ese
 * digest. Es preferible un perfil moderno (TLS mutuo, X.509 o tokens) siempre que la contraparte lo
 * soporte.
 */
public final class WsSecurity {

  static final String WSSE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  static final String WSU =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
  private static final String PROFILE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0";
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Modos de contraseña; no hay valor por defecto, un cliente declara el que exige su contraparte.
   */
  public enum Mode {
    /** {@code PasswordText}: la contraseña viaja dentro del canal TLS. */
    TEXT,
    /**
     * {@code PasswordDigest} de UsernameToken Profile 1.0: SHA-1, exigido por el perfil y no una
     * elección de este framework. Solo para contrapartes que lo exigen, sobre TLS, con el endpoint
     * en la lista de permitidos del servicio; su uso se registra en log al crear el cliente.
     */
    LEGACY_SHA1_DIGEST
  }

  private WsSecurity() {}

  static Element usernameToken(
      Document doc, String username, char[] password, Mode mode, Instant now) {
    Element security = doc.createElementNS(WSSE, "wsse:Security");
    security.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:wsu", WSU);
    Element token = doc.createElementNS(WSSE, "wsse:UsernameToken");
    security.appendChild(token);
    Element user = doc.createElementNS(WSSE, "wsse:Username");
    user.setTextContent(username);
    token.appendChild(user);
    Element pwd = doc.createElementNS(WSSE, "wsse:Password");
    token.appendChild(pwd);
    String created = now.truncatedTo(ChronoUnit.SECONDS).toString();
    if (mode == Mode.TEXT) {
      pwd.setAttribute("Type", PROFILE + "#PasswordText");
      pwd.setTextContent(new String(password));
      return security;
    }
    byte[] nonce = new byte[16];
    RANDOM.nextBytes(nonce);
    pwd.setAttribute("Type", PROFILE + "#PasswordDigest");
    pwd.setTextContent(digest(nonce, created, password));
    Element nonceEl = doc.createElementNS(WSSE, "wsse:Nonce");
    nonceEl.setAttribute(
        "EncodingType",
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary");
    nonceEl.setTextContent(Base64.getEncoder().encodeToString(nonce));
    token.appendChild(nonceEl);
    Element createdEl = doc.createElementNS(WSU, "wsu:Created");
    createdEl.setTextContent(created);
    token.appendChild(createdEl);
    return security;
  }

  @SuppressFBWarnings(
      value = "WEAK_MESSAGE_DIGEST_SHA1",
      justification =
          "Explicit opt-in WS-Security UsernameToken interoperability requires SHA-1; TLS "
              + "remains mandatory outside the documented local exception.")
  static String digest(byte[] nonce, String created, char[] password) {
    try {
      // Exigido por el protocolo de digest UsernameToken de WS-Security heredado, seleccionado de
      // forma explícita.
      // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(nonce);
      sha1.update(created.getBytes(StandardCharsets.UTF_8));
      var encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
      try {
        sha1.update(encoded);
      } finally {
        encoded.clear();
        while (encoded.hasRemaining()) encoded.put((byte) 0);
      }
      return Base64.getEncoder().encodeToString(sha1.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new SoapException("SHA-1 not available", e);
    }
  }
}
