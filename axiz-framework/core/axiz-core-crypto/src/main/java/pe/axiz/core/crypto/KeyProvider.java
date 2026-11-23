package pe.axiz.core.crypto;

import java.util.Optional;
import javax.crypto.SecretKey;

/**
 * Suministra claves simétricas por id. Los datos nuevos se cifran con la clave actual; los datos
 * existentes conservan el id de la clave que los cifró, lo que permite la rotación sin interrupción
 * del servicio.
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
