package pe.axiz.core.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 request signatures for partner callbacks and webhooks. The signed string is {@code
 * timestamp + "." + method + "." + path + "." + body}; verification checks the time window to
 * prevent replays and compares in constant time.
 */
public final class RequestSignature {

  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] key;
  private final Duration tolerance;
  private final Clock clock;

  public RequestSignature(byte[] key, Duration tolerance, Clock clock) {
    if (key.length < 32) {
      throw new IllegalArgumentException("Signature key must have at least 256 bits");
    }
    this.key = key.clone();
    this.tolerance = Objects.requireNonNull(tolerance);
    this.clock = Objects.requireNonNull(clock);
  }

  public String sign(Instant timestamp, String method, String path, String body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      String payload = timestamp.getEpochSecond() + "." + method + "." + path + "." + body;
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC not available", e);
    }
  }

  /** Verifies the signature and the timestamp window. */
  public boolean verify(
      Instant timestamp, String method, String path, String body, String signature) {
    Instant now = clock.instant();
    if (timestamp.isBefore(now.minus(tolerance)) || timestamp.isAfter(now.plus(tolerance))) {
      return false;
    }
    byte[] expected = sign(timestamp, method, path, body).getBytes(StandardCharsets.US_ASCII);
    byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, actual);
  }
}
