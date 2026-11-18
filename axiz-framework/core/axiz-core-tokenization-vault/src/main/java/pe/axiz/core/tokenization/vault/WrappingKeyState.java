package pe.axiz.core.tokenization.vault;

import java.util.List;

/** Independent durable write/read generation for envelope wrapping keys, never HMAC material. */
public record WrappingKeyState(
    String keysetId, long version, String writeKeyId, List<String> readKeyIds) {
  public WrappingKeyState {
    // The bounded identifier/set invariants are identical; the domain types remain distinct.
    var checked = new FingerprintKeyState(keysetId, version, writeKeyId, readKeyIds);
    keysetId = checked.keysetId();
    writeKeyId = checked.writeKeyId();
    readKeyIds = List.copyOf(checked.readKeyIds());
  }
}
