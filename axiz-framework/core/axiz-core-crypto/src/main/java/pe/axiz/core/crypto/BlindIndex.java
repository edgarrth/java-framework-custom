package pe.axiz.core.crypto;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Índices de igualdad versionados que usan una clave HMAC dedicada, alcance explícito y
 * delimitación sin pérdida. La canonicalización corresponde al perfil del campo; esta primitiva
 * nunca modifica el valor suministrado.
 */
public final class BlindIndex implements AutoCloseable {

  private final Map<String, byte[]> keys;
  private final String currentKeyId;
  private boolean closed;

  public BlindIndex(Map<String, byte[]> keys, String currentKeyId) {
    if (keys.isEmpty() || keys.size() > 16 || !keys.containsKey(currentKeyId))
      throw new CryptoException("Index read keys must include the write key");
    Map<String, byte[]> snapshot = new LinkedHashMap<>();
    keys.forEach(
        (id, key) -> {
          if (!id.matches("[A-Za-z0-9_-]{1,32}") || key.length < 32 || key.length > 1024)
            throw new CryptoException("Invalid index key configuration");
        });
    keys.forEach((id, key) -> snapshot.put(id, key.clone()));
    this.keys = Map.copyOf(snapshot);
    this.currentKeyId = currentKeyId;
  }

  public KeyedHash index(String canonicalValue, IndexScope scope) {
    return index(canonicalValue, scope, currentKeyId);
  }

  public KeyedHash index(String canonicalValue, IndexScope scope, String keyId) {
    return compute(canonicalValue, scope, keyId);
  }

  /**
   * No crea ningún String inmutable en texto plano; las copias de trabajo privadas se borran tras
   * el hash.
   */
  public KeyedHash index(char[] canonicalValue, IndexScope scope, String keyId) {
    char[] owned = canonicalValue.clone();
    try {
      return compute(CharBuffer.wrap(owned), scope, keyId);
    } finally {
      Arrays.fill(owned, '\0');
    }
  }

  private synchronized KeyedHash compute(
      CharSequence canonicalValue, IndexScope scope, String keyId) {
    if (closed) throw new CryptoException("Index keyring has been closed");
    byte[] key = keys.get(keyId);
    if (key == null) throw new CryptoException("Required index key is unavailable");
    byte[] preimage = IndexPreimage.of(canonicalValue, scope, keyId);
    try {
      return new KeyedHash(keyId, scope, Hashing.hmacSha256(key, preimage));
    } finally {
      Arrays.fill(preimage, (byte) 0);
    }
  }

  /**
   * El mapa configurado contiene solo claves de lectura activas; la clave de escritura se devuelve
   * primero.
   */
  public List<KeyedHash> candidates(String canonicalValue, IndexScope scope) {
    List<KeyedHash> result = new ArrayList<>();
    result.add(index(canonicalValue, scope));
    keys.keySet().stream()
        .filter(id -> !id.equals(currentKeyId))
        .sorted()
        .forEach(id -> result.add(index(canonicalValue, scope, id)));
    return List.copyOf(result);
  }

  public Set<String> keyIds() {
    return keys.keySet();
  }

  @Override
  public synchronized void close() {
    keys.values().forEach(key -> Arrays.fill(key, (byte) 0));
    closed = true;
  }
}
