package pe.axiz.core.tokenization.vault;

import java.util.UUID;
import pe.axiz.core.tokenization.TokenOperation;

/**
 * Distributed, fail-closed admission; implementations enforce subject/client and tenant windows.
 */
public interface TokenBudget {
  Permit acquire(TokenCaller caller, TokenOperation operation, VaultScope scope);

  /** Associates admission with the audit operation; providers can retain this recovery identity. */
  default Permit acquire(
      TokenCaller caller, TokenOperation operation, VaultScope scope, UUID operationId) {
    return acquire(caller, operation, scope);
  }

  void beforeWrite(TokenCaller caller, VaultScope scope);

  /** Whether limits and leases are atomic across every replica in this deployment. */
  boolean distributed();

  /** Owned concurrency lease, released after the operation actually ends. */
  interface Permit extends AutoCloseable {
    @Override
    void close();
  }
}
