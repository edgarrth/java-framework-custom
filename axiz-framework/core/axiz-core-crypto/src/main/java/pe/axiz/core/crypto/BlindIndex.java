package pe.axiz.core.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned equality indexes using a dedicated HMAC key, explicit scope and lossless framing.
 * Canonicalization belongs to the field profile; this primitive never modifies the supplied value.
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

  public synchronized KeyedHash index(String canonicalValue, IndexScope scope, String keyId) {
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

  /** The configured map contains active read keys only; the write key is returned first. */
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
