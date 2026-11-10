package pe.axiz.core.soap;

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
 * WS-Security UsernameToken header for legacy SOAP counterparties. Both modes are legacy
 * authentication and are only used over TLS (the client refuses plain HTTP unless a local stand-in
 * is explicitly allowed): {@link Mode#TEXT} sends the password inside the TLS channel; {@link
 * Mode#LEGACY_SHA1_DIGEST} sends {@code Base64(SHA-1(nonce + created + password))} with nonce and
 * timestamp, because the UsernameToken Profile 1.0 fixes SHA-1 for that digest. Prefer a modern
 * profile (mutual TLS, X.509 or tokens) whenever the counterparty supports one.
 */
public final class WsSecurity {

  static final String WSSE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  static final String WSU =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
  private static final String PROFILE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0";
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Password modes; there is no default, a client declares the one its counterparty requires. */
  public enum Mode {
    /** {@code PasswordText}: the password travels inside the TLS channel. */
    TEXT,
    /**
     * {@code PasswordDigest} of the UsernameToken Profile 1.0: SHA-1, required by the profile and
     * not a choice of this framework. Only for counterparties that demand it, over TLS, with the
     * endpoint on the allow-list of the service; its use is logged when the client is created.
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

  static String digest(byte[] nonce, String created, char[] password) {
    try {
      // Required by the explicitly selected legacy WS-Security UsernameToken digest protocol.
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
