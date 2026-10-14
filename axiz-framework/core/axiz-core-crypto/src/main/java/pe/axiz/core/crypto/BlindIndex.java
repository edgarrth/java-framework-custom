package pe.axiz.core.crypto;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * Blind index for searching encrypted columns by equality: HMAC-SHA256 of the normalized value with
 * a dedicated key (never the encryption key), truncated to limit leakage.
 */
public final class BlindIndex {

  private final byte[] key;
  private final int bytes;

  public BlindIndex(byte[] key, int bytes) {
    if (key.length < 32) {
      throw new CryptoException("Blind index keys must have at least 256 bits");
    }
    if (bytes < 8 || bytes > 32) {
      throw new CryptoException("Blind index size must be between 8 and 32 bytes");
    }
    this.key = key.clone();
    this.bytes = bytes;
  }

  public String index(String value, String context) {
    String normalized =
        Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
            .toUpperCase(Locale.ROOT)
            .replaceAll("[\\s-]", "");
    byte[] mac =
        Hashing.hmacSha256(key, (context + "|" + normalized).getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(mac, bytes));
  }
}
