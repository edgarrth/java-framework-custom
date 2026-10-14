package pe.axiz.core.crypto;

import java.util.Optional;
import javax.crypto.SecretKey;

/**
 * Supplies symmetric keys by id. New data is encrypted with the current key; old data keeps the id
 * of the key that encrypted it, which enables rotation without downtime.
 */
public interface KeyProvider {

  String currentKeyId();

  SecretKey key(String keyId);

  default SecretKey currentKey() {
    return key(currentKeyId());
  }

  default Optional<SecretKey> find(String keyId) {
    try {
      return Optional.of(key(keyId));
    } catch (CryptoException e) {
      return Optional.empty();
    }
  }
}
