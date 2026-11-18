package pe.axiz.core.tokenization.vault;

import java.util.UUID;

/** Technical reconciliation alert when a shared concurrency permit may remain reserved. */
@FunctionalInterface
public interface TokenBudgetLeaseAlarm {
  /** No source value, subject, tenant, token or provider exception may be attached. */
  void uncertain(String namespace, UUID leaseId);
}
