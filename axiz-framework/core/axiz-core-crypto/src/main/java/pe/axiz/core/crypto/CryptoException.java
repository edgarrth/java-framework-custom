package pe.axiz.core.crypto;

/** Cryptographic operation failed (wrong key, tampered data, unavailable provider). */
public class CryptoException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CryptoException(String message) {
    super(message);
  }

  public CryptoException(String message, Throwable cause) {
    super(message, cause);
  }
}
