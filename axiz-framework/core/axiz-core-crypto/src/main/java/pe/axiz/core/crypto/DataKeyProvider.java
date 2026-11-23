package pe.axiz.core.crypto;

import java.security.MessageDigest;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Puerto para las claves de datos del cifrado envelope (AWS KMS en producción). */
public interface DataKeyProvider {

  /**
   * Transfiere una clave de datos nueva y propia; quien llama la cierra tras usarla o al retirarla
   * de la caché.
   */
  DataKey generate(String context);

  /**
   * Descifra en un buffer nuevo y propio que quien llama limpia; nunca se devuelve un array de
   * clave compartido.
   */
  byte[] decrypt(byte[] encryptedKey, String context);

  /**
   * Par de claves de datos. Los arrays se copian a la entrada y a la salida; la igualdad compara
   * solo la clave cifrada, en tiempo constante, y el texto plano nunca aparece en {@link
   * #toString()}.
   */
  final class DataKey implements AutoCloseable {
    private final byte[] plaintext;
    private final byte[] encrypted;
    private boolean closed;

    /**
     * Crea un par de claves de datos.
     *
     * @param plaintext clave de 32 bytes, se conserva solo en memoria
     * @param encrypted clave cifrada con la clave maestra
     */
    public DataKey(byte[] plaintext, byte[] encrypted) {
      this.plaintext = plaintext.clone();
      this.encrypted = encrypted.clone();
    }

    /** Copia de la clave en texto plano de 32 bytes. */
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
     * Limpia solo esta instancia; quienes llaman siguen siendo responsables de sus copias de
     * entrada y salida.
     */
    @Override
    public synchronized void close() {
      Arrays.fill(plaintext, (byte) 0);
      closed = true;
    }

    /** Copia de la clave cifrada con la clave maestra. */
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
