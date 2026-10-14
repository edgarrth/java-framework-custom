package pe.axiz.core.id;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Idempotency keys: validation of client keys and deterministic derivation from parts. */
public final class IdempotencyKeys {

  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

  private IdempotencyKeys() {}

  public static boolean isValid(@Nullable String key) {
    return key != null && SAFE.matcher(key).matches();
  }

  public static String newKey() {
    return UuidV7.generate().toString();
  }

  /**
   * Deterministic key from business parts, e.g. {@code derive("payout", batchId, line)}. The same
   * parts always give the same key (base64url SHA-256, 43 characters).
   *
   * @param parts business identifiers
   * @return the derived key
   */
  public static String derive(String... parts) {
    if (parts.length == 0) {
      throw new IllegalArgumentException("At least one part is required");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(intBytes(bytes.length));
        digest.update(bytes);
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static byte[] intBytes(int v) {
    return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
  }
}
