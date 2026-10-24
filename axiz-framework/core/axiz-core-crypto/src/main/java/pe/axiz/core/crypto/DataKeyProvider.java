package pe.axiz.core.crypto;

import java.security.MessageDigest;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Port for envelope encryption data keys (AWS KMS in production). */
public interface DataKeyProvider {

  /** A new data key: plaintext for local use and its encrypted form to store with the data. */
  DataKey generate(String context);

  /** Decrypts a stored data key. */
  byte[] decrypt(byte[] encryptedKey, String context);

  /**
   * Data key pair. The arrays are copied on the way in and out; equality compares the encrypted key
   * only, in constant time, and the plaintext never appears in {@link #toString()}.
   */
  final class DataKey {
    private final byte[] plaintext;
    private final byte[] encrypted;

    /**
     * Creates a data key pair.
     *
     * @param plaintext 32-byte key, keep only in memory
     * @param encrypted key encrypted by the master key
     */
    public DataKey(byte[] plaintext, byte[] encrypted) {
      this.plaintext = plaintext.clone();
      this.encrypted = encrypted.clone();
    }

    /** A copy of the 32-byte plaintext key. */
    public byte[] plaintext() {
      return plaintext.clone();
    }

    /** A copy of the key encrypted by the master key. */
    public byte[] encrypted() {
      return encrypted.clone();
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof DataKey k && MessageDigest.isEqual(encrypted, k.encrypted);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(encrypted);
    }

    @Override
    public String toString() {
      return "DataKey[****]";
    }
  }
}
