package pe.axiz.core.crypto;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.crypto.SecretKey;

/** Proveedor de claves sobre claves cargadas al arrancar (por ejemplo, desde Secrets Manager). */
public final class StaticKeyProvider implements KeyProvider, AutoCloseable {

  private final String currentKeyId;
  private final Map<String, byte[]> keys;
  private final Object lock = new Object();
  private boolean closed;

  public StaticKeyProvider(String currentKeyId, Map<String, SecretKey> keys) {
    this.currentKeyId = Objects.requireNonNull(currentKeyId);
    this.keys =
        snapshot(
            currentKeyId,
            keys,
            key -> {
              if (!"AES".equals(key.getAlgorithm()))
                throw new CryptoException("Field keys must use AES-256");
              byte[] encoded = key.getEncoded();
              if (encoded == null)
                throw new CryptoException(
                    "Static keys must be extractable; use a custom provider for handles");
              return encoded;
            });
  }

  private StaticKeyProvider(Map<String, byte[]> rawKeys, String currentKeyId) {
    this.currentKeyId = Objects.requireNonNull(currentKeyId);
    this.keys = snapshot(currentKeyId, rawKeys, bytes -> bytes.clone());
  }

  private static void validate(String currentKeyId, Map<String, ?> keys) {
    if (keys.isEmpty() || keys.size() > 16 || !keys.containsKey(currentKeyId))
      throw new CryptoException(
          "Field read keys must include the write key and contain at most 16 keys");
    for (String id : keys.keySet()) {
      if (!id.matches("^[A-Za-z0-9_-]{1,32}$")) {
        throw new CryptoException("Invalid key id");
      }
    }
  }

  private static <T> Map<String, byte[]> snapshot(
      String currentKeyId, Map<String, T> keys, Function<T, byte[]> extract) {
    validate(currentKeyId, keys);
    Map<String, byte[]> owned = new LinkedHashMap<>();
    try {
      keys.forEach(
          (id, key) -> {
            byte[] encoded = extract.apply(key);
            if (encoded.length != AesGcm.KEY_BYTES) {
              Arrays.fill(encoded, (byte) 0);
              throw new CryptoException("Field keys must use AES-256");
            }
            owned.put(id, encoded);
          });
      return Map.copyOf(owned);
    } catch (RuntimeException | Error failure) {
      owned.values().forEach(key -> Arrays.fill(key, (byte) 0));
      throw failure;
    }
  }

  /** Construye el proveedor a partir de claves AES-256 codificadas en base64. */
  public static StaticKeyProvider fromBase64(String currentKeyId, Map<String, String> base64Keys) {
    validate(currentKeyId, base64Keys);
    Map<String, byte[]> keys = new LinkedHashMap<>();
    try {
      base64Keys.forEach(
          (id, value) -> {
            if (value.length() < 43 || value.length() > 44)
              throw new CryptoException("Invalid field key encoding");
            try {
              keys.put(id, Base64.getDecoder().decode(value));
            } catch (IllegalArgumentException malformed) {
              throw new CryptoException("Invalid field key encoding");
            }
          });
      return fromBytes(currentKeyId, keys);
    } finally {
      keys.values().forEach(key -> Arrays.fill(key, (byte) 0));
    }
  }

  /** Copia la entrada; quienes llaman conservan la propiedad y deben limpiar sus buffers. */
  public static StaticKeyProvider fromBytes(String currentKeyId, Map<String, byte[]> rawKeys) {
    return new StaticKeyProvider(rawKeys, currentKeyId);
  }

  @Override
  public String currentKeyId() {
    return currentKeyId;
  }

  @Override
  public SecretKey key(String keyId) {
    synchronized (lock) {
      if (closed) throw new CryptoException("Field key provider has been closed");
      byte[] key = keys.get(keyId);
      if (key == null) {
        throw new CryptoException("Unknown field key id");
      }
      return AesGcm.key(key);
    }
  }

  /**
   * Validación de separación de claves al arrancar; compara todas las claves de lectura sin exponer
   * sus bytes.
   */
  public boolean containsKeyMaterial(byte[] candidate) {
    synchronized (lock) {
      Objects.requireNonNull(candidate);
      if (closed) throw new CryptoException("Field key provider has been closed");
      boolean found = false;
      for (byte[] key : keys.values()) found |= MessageDigest.isEqual(key, candidate);
      return found;
    }
  }

  /**
   * Borra el material propio; no puede garantizarse que las copias dentro de un proveedor JCE sean
   * borrables.
   */
  @Override
  public void close() {
    synchronized (lock) {
      keys.values().forEach(key -> Arrays.fill(key, (byte) 0));
      closed = true;
    }
  }
}
