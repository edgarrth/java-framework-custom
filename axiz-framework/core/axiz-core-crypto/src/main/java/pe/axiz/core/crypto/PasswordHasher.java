package pe.axiz.core.crypto;

/**
 * Puerto de hash de contraseñas. Las implementaciones Argon2id y BCrypt provienen del starter de
 * criptografía.
 */
public interface PasswordHasher {

  String hash(char[] password);

  boolean matches(char[] password, String encoded);

  /**
   * Indica si el hash almacenado usa parámetros más débiles y debería actualizarse en el siguiente
   * inicio de sesión.
   */
  default boolean needsUpgrade(String encoded) {
    return false;
  }
}
