package pe.axiz.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** SHA-2 hashes and HMAC with constant-time verification. */
public final class Hashing {

  private Hashing() {}

  public static byte[] sha256(byte[] data) {
    return digest("SHA-256", data);
  }

  public static byte[] sha512(byte[] data) {
    return digest("SHA-512", data);
  }

  public static String sha256Hex(String text) {
    return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
  }

  public static byte[] hmacSha256(byte[] key, byte[] data) {
    return mac("HmacSHA256", key, data);
  }

  public static byte[] hmacSha512(byte[] key, byte[] data) {
    return mac("HmacSHA512", key, data);
  }

  /** Constant-time comparison of a received MAC. */
  public static boolean verifyHmacSha256(byte[] key, byte[] data, byte[] received) {
    return MessageDigest.isEqual(hmacSha256(key, data), received);
  }

  private static byte[] digest(String algorithm, byte[] data) {
    try {
      return MessageDigest.getInstance(algorithm).digest(data);
    } catch (GeneralSecurityException e) {
      throw new CryptoException(algorithm + " not available", e);
    }
  }

  private static byte[] mac(String algorithm, byte[] key, byte[] data) {
    if (key.length < 32) {
      throw new CryptoException("HMAC keys must have at least 256 bits");
    }
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(key, algorithm));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new CryptoException(algorithm + " failed", e);
    }
  }
}
