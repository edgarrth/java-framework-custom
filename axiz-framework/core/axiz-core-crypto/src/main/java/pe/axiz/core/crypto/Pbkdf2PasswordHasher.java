package pe.axiz.core.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * PBKDF2-HMAC-SHA256 password hasher available in any JDK (FIPS friendly). Format: {@code
 * $pbkdf2-sha256$i=<iterations>$<salt>$<hash>}.
 *
 * <p>Stored values are parsed by one validating reader: an unknown or corrupt format, a salt or
 * hash of an unexpected size, or an iteration count outside {@link #MIN_ITERATIONS}..{@link
 * #MAX_ITERATIONS} never matches and always asks for an upgrade, instead of throwing or letting a
 * tampered record burn CPU on every login.
 */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

  public static final int DEFAULT_ITERATIONS = 600_000;

  /** Fewest iterations accepted for new hashes and for stored ones. */
  public static final int MIN_ITERATIONS = 100_000;

  /** Most iterations accepted from a stored hash (cost cap against tampered records). */
  public static final int MAX_ITERATIONS = 10_000_000;

  static final int SALT_BYTES = 16;
  static final int HASH_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String PREFIX = "$pbkdf2-sha256$i=";

  private final int iterations;

  public Pbkdf2PasswordHasher(int iterations) {
    if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
      throw new CryptoException(
          "PBKDF2 needs between " + MIN_ITERATIONS + " and " + MAX_ITERATIONS + " iterations");
    }
    this.iterations = iterations;
  }

  @Override
  public String hash(char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    byte[] hash = derive(password, salt, iterations);
    Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
    return PREFIX + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
  }

  @Override
  public boolean matches(char[] password, String encoded) {
    Optional<Stored> stored = parse(encoded);
    if (stored.isEmpty()) {
      return false;
    }
    Stored s = stored.get();
    return MessageDigest.isEqual(s.hash(), derive(password, s.salt(), s.iterations()));
  }

  @Override
  public boolean needsUpgrade(String encoded) {
    return parse(encoded).map(s -> s.iterations() < iterations).orElse(true);
  }

  /** A stored hash, only when every part is well formed and within bounds. */
  static Optional<Stored> parse(@Nullable String encoded) {
    if (encoded == null || !encoded.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String[] parts = encoded.substring(PREFIX.length()).split("\\$", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[0].length() > 9) {
      return Optional.empty();
    }
    for (int i = 0; i < parts[0].length(); i++) {
      if (!Character.isDigit(parts[0].charAt(i))) {
        return Optional.empty();
      }
    }
    int its = Integer.parseInt(parts[0]);
    if (its < MIN_ITERATIONS || its > MAX_ITERATIONS) {
      return Optional.empty();
    }
    try {
      byte[] salt = Base64.getDecoder().decode(parts[1]);
      byte[] hash = Base64.getDecoder().decode(parts[2]);
      if (salt.length != SALT_BYTES || hash.length != HASH_BYTES) {
        return Optional.empty();
      }
      return Optional.of(new Stored(its, salt, hash));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** A parsed stored hash; its arrays are owned by this class and never handed out. */
  static final class Stored {
    private final int iterations;
    private final byte[] salt;
    private final byte[] hash;

    Stored(int iterations, byte[] salt, byte[] hash) {
      this.iterations = iterations;
      this.salt = salt.clone();
      this.hash = hash.clone();
    }

    int iterations() {
      return iterations;
    }

    byte[] salt() {
      return salt.clone();
    }

    byte[] hash() {
      return hash.clone();
    }
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BYTES * 8);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("PBKDF2 not available", e);
    } finally {
      spec.clearPassword();
    }
  }
}
