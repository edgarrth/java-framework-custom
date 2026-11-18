package pe.axiz.payments.orders.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.kit.jpa.AuditableEntity;

/**
 * Row of {@code payment_order}. Account numbers are encrypted (AES-GCM keys from the secret store,
 * rotation by key id); the creditor account also has a blind index (HMAC) to search by it without
 * decrypting.
 */
@Entity
@Table(name = "payment_order")
public class PaymentOrderEntity extends AuditableEntity {

  @Column(name = "operation_number", nullable = false, updatable = false, length = 40)
  private String operationNumber = "";

  @Column(name = "customer_id", nullable = false, updatable = false, length = 20)
  private String customerId = "";

  @Convert(converter = DebtorAccountConverter.class)
  @Column(name = "debtor_account", nullable = false, updatable = false, length = 200)
  private String debtorAccount = "";

  @Convert(converter = CreditorAccountConverter.class)
  @Column(name = "creditor_cci", nullable = false, updatable = false, length = 200)
  private String creditorCci = "";

  @Column(name = "creditor_cci_index", nullable = false, updatable = false, length = 256)
  private String creditorCciIndex = "";

  @Column(name = "amount", nullable = false, updatable = false, precision = 18, scale = 2)
  private BigDecimal amount = BigDecimal.ZERO;

  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency = "";

  @Column(name = "description", length = 140)
  private @Nullable String description;

  @Column(name = "processing_date", nullable = false, updatable = false)
  private LocalDate processingDate = LocalDate.EPOCH;

  @Column(name = "request_key", updatable = false, length = 64)
  private @Nullable String requestKey;

  @Column(name = "status", nullable = false, length = 16)
  private String status = "";

  @Column(name = "status_reason", length = 200)
  private @Nullable String statusReason;

  protected PaymentOrderEntity() {}

  PaymentOrderEntity(
      UUID id,
      String operationNumber,
      String customerId,
      String debtorAccount,
      String creditorCci,
      String creditorCciIndex,
      BigDecimal amount,
      String currency,
      @Nullable String description,
      LocalDate processingDate) {
    setId(id);
    this.operationNumber = operationNumber;
    this.customerId = customerId;
    this.debtorAccount = debtorAccount;
    this.creditorCci = creditorCci;
    this.creditorCciIndex = creditorCciIndex;
    this.amount = amount;
    this.currency = currency;
    this.description = description;
    this.processingDate = processingDate;
  }

  void requestKey(@Nullable String key) {
    this.requestKey = key;
  }

  void status(String newStatus, @Nullable String reason) {
    this.status = newStatus;
    this.statusReason = reason;
  }

  String getOperationNumber() {
    return operationNumber;
  }

  String getCustomerId() {
    return customerId;
  }

  String getDebtorAccount() {
    return debtorAccount;
  }

  String getCreditorCci() {
    return creditorCci;
  }

  BigDecimal getAmount() {
    return amount;
  }

  String getCurrency() {
    return currency;
  }

  @Nullable String getDescription() {
    return description;
  }

  LocalDate getProcessingDate() {
    return processingDate;
  }

  String getStatus() {
    return status;
  }

  @Nullable String getStatusReason() {
    return statusReason;
  }
}
