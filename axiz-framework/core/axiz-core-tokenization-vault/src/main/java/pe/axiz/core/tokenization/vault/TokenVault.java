package pe.axiz.core.tokenization.vault;

import java.util.List;
import java.util.Optional;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;

/**
 * Strongly consistent storage; writes are atomic and unknown outcomes throw
 * VaultUncertainException.
 */
public interface TokenVault {
  FingerprintKeyState keyState(String keysetId);

  Optional<VaultRecord> findByFingerprint(VaultScope scope, List<KeyedHash> candidates);

  PutOutcome putIfAbsent(
      VaultRecord record, FingerprintKeyState expected, List<KeyedHash> otherReadFingerprints);

  Optional<VaultRecord> find(VaultScope scope, Token token);

  DeleteOutcome delete(VaultRecord expected);
}
