package pe.axiz.core.crypto;

/** Password hashing port. Argon2id and BCrypt implementations come from the crypto starter. */
public interface PasswordHasher {

  String hash(char[] password);

  boolean matches(char[] password, String encoded);

  /** Whether the stored hash uses weaker parameters and should be upgraded at next login. */
  default boolean needsUpgrade(String encoded) {
    return false;
  }
}
