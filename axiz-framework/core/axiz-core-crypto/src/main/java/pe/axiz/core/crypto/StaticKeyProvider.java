package pe.axiz.core.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.SecretKey;

/** Key provider over keys loaded at startup (for example from Secrets Manager). */
public final class StaticKeyProvider implements KeyProvider {

  private final String currentKeyId;
  private final Map<String, SecretKey> keys;

  public StaticKeyProvider(String currentKeyId, Map<String, SecretKey> keys) {
    this.currentKeyId = Objects.requireNonNull(currentKeyId);
    this.keys = Map.copyOf(keys);
    if (!this.keys.containsKey(currentKeyId)) {
      throw new CryptoException("Current key " + currentKeyId + " is not among the provided keys");
    }
    for (String id : this.keys.keySet()) {
      if (!id.matches("^[A-Za-z0-9_-]{1,32}$")) {
        throw new CryptoException("Invalid key id " + id);
      }
    }
  }

  /** Builds the provider from base64 encoded AES-256 keys. */
  public static StaticKeyProvider fromBase64(String currentKeyId, Map<String, String> base64Keys) {
    Map<String, SecretKey> keys = new LinkedHashMap<>();
    base64Keys.forEach((id, value) -> keys.put(id, AesGcm.key(Base64.getDecoder().decode(value))));
    return new StaticKeyProvider(currentKeyId, keys);
  }

  @Override
  public String currentKeyId() {
    return currentKeyId;
  }

  @Override
  public SecretKey key(String keyId) {
    SecretKey key = keys.get(keyId);
    if (key == null) {
      throw new CryptoException("Unknown key id " + keyId);
    }
    return key;
  }
}
