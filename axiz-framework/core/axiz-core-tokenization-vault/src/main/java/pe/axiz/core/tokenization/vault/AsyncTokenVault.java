package pe.axiz.core.tokenization.vault;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;

/**
 * Almacenamiento fuertemente consistente; las escrituras son atómicas y los resultados desconocidos
 * lanzan VaultUncertainException.
 */
public interface AsyncTokenVault {
  CompletionStage<FingerprintKeyState> keyState(String keysetId);

  CompletionStage<Optional<VaultRecord>> findByFingerprint(
      VaultScope scope, List<KeyedHash> candidates);

  CompletionStage<PutOutcome> putIfAbsent(
      VaultRecord record, FingerprintKeyState expected, List<KeyedHash> otherReadFingerprints);

  CompletionStage<Optional<VaultRecord>> find(VaultScope scope, Token token);

  CompletionStage<DeleteOutcome> delete(VaultRecord expected);
}
