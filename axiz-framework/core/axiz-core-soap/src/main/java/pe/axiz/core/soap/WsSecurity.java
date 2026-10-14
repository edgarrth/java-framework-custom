package pe.axiz.core.soap;

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
 * WS-Security UsernameToken header. {@link Mode#DIGEST} sends {@code Base64(SHA-1(nonce + created +
 * password))} with nonce and timestamp, as the profile defines; {@link Mode#TEXT} is only
 * acceptable over TLS.
 */
public final class WsSecurity {

  static final String WSSE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  static final String WSU =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
  private static final String PROFILE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0";
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Password modes. */
  public enum Mode {
    TEXT,
    DIGEST
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
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(nonce);
      sha1.update(created.getBytes(StandardCharsets.UTF_8));
      sha1.update(new String(password).getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(sha1.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new SoapException("SHA-1 not available", e);
    }
  }
}
