package pe.axiz.payments.history.adapter.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.data.relational.core.mapping.Table;
import pe.axiz.core.money.Money;
import pe.axiz.kit.r2dbc.EncryptedText;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import pe.axiz.spring.r2dbc.AuditableRow;

/**
 * Row of {@code payment_history}: UUIDv7 id, optimistic locking and auditing columns come from
 * {@link AuditableRow}; the creditor account is an {@link EncryptedText} column (AES-GCM with the
 * field keys of the secret store) and the amount a {@link Money} column, both converted by the axiz
 * R2DBC starter.
 */
@Table("payment_history")
public class PaymentHistoryRow extends AuditableRow {

  private String paymentId = "";
  private String customerId = "";
  private @Nullable String operationNumber;
  private @Nullable EncryptedText creditorCci;
  private @Nullable Money amount;
  private @Nullable LocalDate processingDate;
  private String status = "";
  private @Nullable String reason;
  private Instant lastChangeAt = Instant.EPOCH;

  /** Used by Spring Data to read rows. */
  protected PaymentHistoryRow() {}

  static PaymentHistoryRow of(PaymentHistory h) {
    PaymentHistoryRow row = new PaymentHistoryRow();
    row.copy(h);
    return row;
  }

  /** Copies the projected state into this row, keeping its id, version and audit columns. */
  PaymentHistoryRow copy(PaymentHistory h) {
    paymentId = h.paymentId();
    customerId = h.customerId();
    operationNumber = h.operationNumber();
    String cci = h.creditorCci();
    creditorCci = cci == null ? null : EncryptedText.of(cci);
    amount = h.amount();
    processingDate = h.processingDate();
    status = h.status().name();
    reason = h.reason();
    lastChangeAt = h.lastChangeAt();
    return this;
  }

  PaymentHistory toDomain() {
    EncryptedText cci = creditorCci;
    return new PaymentHistory(
        paymentId,
        customerId,
        operationNumber,
        cci == null ? null : cci.value(),
        amount,
        processingDate,
        PaymentStatus.valueOf(status),
        reason,
        lastChangeAt);
  }
}
