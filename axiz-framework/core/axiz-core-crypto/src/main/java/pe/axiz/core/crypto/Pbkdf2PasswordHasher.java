package pe.axiz.core.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hasher available in any JDK (FIPS friendly). Format: {@code
 * $pbkdf2-sha256$i=<iterations>$<salt>$<hash>}.
 */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

  public static final int DEFAULT_ITERATIONS = 600_000;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String PREFIX = "$pbkdf2-sha256$i=";

  private final int iterations;

  public Pbkdf2PasswordHasher(int iterations) {
    if (iterations < 100_000) {
      throw new CryptoException("PBKDF2 needs at least 100000 iterations");
    }
    this.iterations = iterations;
  }

  @Override
  public String hash(char[] password) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[] hash = derive(password, salt, iterations);
    Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
    return PREFIX + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
  }

  @Override
  public boolean matches(char[] password, String encoded) {
    try {
      if (!encoded.startsWith(PREFIX)) {
        return false;
      }
      String[] parts = encoded.substring(PREFIX.length()).split("\\$");
      if (parts.length != 3) {
        return false;
      }
      int its = Integer.parseInt(parts[0]);
      byte[] salt = Base64.getDecoder().decode(parts[1]);
      byte[] expected = Base64.getDecoder().decode(parts[2]);
      return MessageDigest.isEqual(expected, derive(password, salt, its));
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public boolean needsUpgrade(String encoded) {
    if (!encoded.startsWith(PREFIX)) {
      return true;
    }
    String its = encoded.substring(PREFIX.length(), encoded.indexOf('$', PREFIX.length()));
    return Integer.parseInt(its) < iterations;
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("PBKDF2 not available", e);
    } finally {
      spec.clearPassword();
    }
  }
}
