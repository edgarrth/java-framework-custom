package pe.axiz.core.crypto;

import java.security.MessageDigest;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Port for envelope encryption data keys (AWS KMS in production). */
public interface DataKeyProvider {

  /** Transfers a new owned data key; the caller closes it after use or cache retirement. */
  DataKey generate(String context);

  /** Decrypts to a fresh owned buffer which the caller clears; never return a shared key array. */
  byte[] decrypt(byte[] encryptedKey, String context);

  /**
   * Data key pair. The arrays are copied on the way in and out; equality compares the encrypted key
   * only, in constant time, and the plaintext never appears in {@link #toString()}.
   */
  final class DataKey implements AutoCloseable {
    private final byte[] plaintext;
    private final byte[] encrypted;
    private boolean closed;

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
    public synchronized byte[] plaintext() {
      if (closed) throw new CryptoException("Data key has been closed");
      return plaintext.clone();
    }

    synchronized DataKey snapshot() {
      if (closed) throw new CryptoException("Data key has been closed");
      return new DataKey(plaintext, encrypted);
    }

    synchronized boolean validForEnvelope() {
      if (closed
          || plaintext.length != AesGcm.KEY_BYTES
          || encrypted.length < 1
          || encrypted.length > 0xFFFF) throw new CryptoException("Invalid envelope data key");
      return true;
    }

    /**
     * Clears this instance only; callers retain responsibility for their input and output copies.
     */
    @Override
    public synchronized void close() {
      Arrays.fill(plaintext, (byte) 0);
      closed = true;
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
