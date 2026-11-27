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
 * Fila de {@code payment_history}: el id UUIDv7, el bloqueo optimista y las columnas de auditoría
 * provienen de {@link AuditableRow}; la cuenta del acreedor es una columna {@link EncryptedText}
 * (AES-GCM con las claves de campo del almacén de secretos) y el monto una columna {@link Money},
 * ambas convertidas por el starter R2DBC de axiz.
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

  /** Lo usa Spring Data para leer filas. */
  protected PaymentHistoryRow() {}

  static PaymentHistoryRow of(PaymentHistory h) {
    PaymentHistoryRow row = new PaymentHistoryRow();
    row.copy(h);
    return row;
  }

  /**
   * Copia el estado proyectado en esta fila y conserva su id, su versión y sus columnas de
   * auditoría.
   */
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
