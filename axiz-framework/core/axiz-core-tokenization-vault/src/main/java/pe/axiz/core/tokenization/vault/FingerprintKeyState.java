package pe.axiz.core.tokenization.vault;

import java.util.HashSet;
import java.util.List;
import pe.axiz.core.tokenization.TokenErrors;

/** Durable optimistic barrier for writers and privileged key rotation. */
public record FingerprintKeyState(
    String keysetId, long version, String writeKeyId, List<String> readKeyIds) {
  public FingerprintKeyState {
    keysetId = VaultIdentifiers.identifier(keysetId, 64);
    writeKeyId = VaultIdentifiers.key(writeKeyId);
    if (version < 1
        || readKeyIds.isEmpty()
        || readKeyIds.size() > 16
        || !readKeyIds.contains(writeKeyId)
        || new HashSet<>(readKeyIds).size() != readKeyIds.size())
      throw TokenErrors.failure(TokenErrors.INVALID);
    readKeyIds.forEach(VaultIdentifiers::key);
    readKeyIds = List.copyOf(readKeyIds);
  }
}
