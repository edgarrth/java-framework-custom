package pe.axiz.core.crypto;

/** Port for envelope encryption data keys (AWS KMS in production). */
public interface DataKeyProvider {

  /** A new data key: plaintext for local use and its encrypted form to store with the data. */
  DataKey generate(String context);

  /** Decrypts a stored data key. */
  byte[] decrypt(byte[] encryptedKey, String context);

  /**
   * Data key pair.
   *
   * @param plaintext 32-byte key, keep only in memory
   * @param encrypted key encrypted by the master key
   */
  record DataKey(byte[] plaintext, byte[] encrypted) {
    public DataKey {
      plaintext = plaintext.clone();
      encrypted = encrypted.clone();
    }

    @Override
    public byte[] plaintext() {
      return plaintext.clone();
    }

    @Override
    public byte[] encrypted() {
      return encrypted.clone();
    }

    @Override
    public String toString() {
      return "DataKey[****]";
    }
  }
}
