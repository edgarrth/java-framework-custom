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
 * Hasher de contraseñas PBKDF2-HMAC-SHA256 disponible en cualquier JDK (compatible con FIPS).
 * Formato: {@code $pbkdf2-sha256$i=<iterations>$<salt>$<hash>}.
 *
 * <p>Los valores almacenados se parsean con un único lector que valida: un formato desconocido o
 * corrupto, un salt o hash de tamaño inesperado, o un número de iteraciones fuera de {@link
 * #MIN_ITERATIONS}..{@link #MAX_ITERATIONS} nunca coincide y siempre pide actualización, en lugar
 * de lanzar una excepción o dejar que un registro alterado consuma CPU en cada inicio de sesión.
 */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

  public static final int DEFAULT_ITERATIONS = 600_000;

  /** Mínimo de iteraciones aceptado para hashes nuevos y almacenados. */
  public static final int MIN_ITERATIONS = 100_000;

  /**
   * Máximo de iteraciones aceptado de un hash almacenado (tope de costo frente a registros
   * alterados).
   */
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

  /** Hash almacenado, solo cuando todas sus partes están bien formadas y dentro de los límites. */
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

  /** Hash almacenado ya parseado; sus arrays son propiedad de esta clase y nunca se entregan. */
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
