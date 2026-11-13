package pe.axiz.core.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.commons.tx.TransactionRunner;

/**
 * Explicit recovery; the audit callback participates in the same transaction as the state change.
 */
public final class OutboxAdmin {
  /** Must record recovery evidence in the transaction that changes the outbox row. */
  @FunctionalInterface
  public interface RecoveryAudit {
    void record(String action, String eventId, String reason, Instant at);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "OutboxStore is a borrowed collaborator; sharing its identity preserves lifecycle, "
              + "transactions or instrumentation. The field is not an owned mutable value.")
  private final OutboxStore store;

  private final TransactionRunner transactions;
  private final RecoveryAudit audit;
  private final Clock clock;

  public OutboxAdmin(
      OutboxStore store, TransactionRunner transactions, RecoveryAudit audit, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.transactions = Objects.requireNonNull(transactions);
    this.audit = Objects.requireNonNull(audit);
    this.clock = Objects.requireNonNull(clock);
  }

  public boolean retry(String id) {
    return recover("retry", id, "explicit-retry");
  }

  public boolean skip(String id, String reason) {
    if (reason.isBlank() || reason.length() > 1000)
      throw new IllegalArgumentException("skip requires a reason of at most 1000 characters");
    return recover("skip", id, reason);
  }

  private boolean recover(String action, String id, String reason) {
    try {
      return transactions.inTransaction(
          () -> {
            boolean changed = action.equals("retry") ? store.retry(id) : store.skip(id, reason);
            if (changed) audit.record(action, id, reason, clock.instant());
            return changed;
          });
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new OutboxException("Outbox recovery failed", failure);
    }
  }
}
