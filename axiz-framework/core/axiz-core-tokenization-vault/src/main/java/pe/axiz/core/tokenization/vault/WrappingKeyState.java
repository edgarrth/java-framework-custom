package pe.axiz.core.tokenization.vault;

import java.util.List;

/**
 * Generación durable e independiente de escritura y lectura para las claves de envoltura del
 * envelope, nunca material HMAC.
 */
public record WrappingKeyState(
    String keysetId, long version, String writeKeyId, List<String> readKeyIds) {
  public WrappingKeyState {
    // Las invariantes de identificador y de conjunto acotados son idénticas; los tipos de dominio
    // siguen siendo distintos.
    var checked = new FingerprintKeyState(keysetId, version, writeKeyId, readKeyIds);
    keysetId = checked.keysetId();
    writeKeyId = checked.writeKeyId();
    readKeyIds = List.copyOf(checked.readKeyIds());
  }
}
