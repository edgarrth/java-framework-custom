package pe.axiz.payments.orders.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;

/**
 * A payment order: the debtor account of a customer pays a creditor account (CCI). Plain Java;
 * persistence and transport live in the adapters.
 */
public final class PaymentOrder {

  private final String id;
  private final String operationNumber;
  private final String customerId;
  private final String debtorAccount;
  private final String creditorCci;
  private final Money amount;
  private final @Nullable String description;
  private final LocalDate processingDate;
  private final Instant createdAt;
  private PaymentStatus status;
  private @Nullable String statusReason;

  /** Rebuilds an order from its stored state. */
  public PaymentOrder(
      @NonNull String id,
      @NonNull String operationNumber,
      @NonNull String customerId,
      @NonNull String debtorAccount,
      @NonNull String creditorCci,
      @NonNull Money amount,
      @Nullable String description,
      @NonNull LocalDate processingDate,
      @NonNull Instant createdAt,
      @NonNull PaymentStatus status,
      @Nullable String statusReason) {
    this.id = id;
    this.operationNumber = operationNumber;
    this.customerId = customerId;
    this.debtorAccount = debtorAccount;
    this.creditorCci = creditorCci;
    this.amount = amount;
    this.description = description;
    this.processingDate = processingDate;
    this.createdAt = createdAt;
    this.status = status;
    this.statusReason = statusReason;
  }

  /** A new order, received and waiting to be processed. */
  public static PaymentOrder register(
      String id,
      String operationNumber,
      String customerId,
      String debtorAccount,
      String creditorCci,
      Money amount,
      @Nullable String description,
      LocalDate processingDate,
      Instant now) {
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("amount must be positive");
    }
    return new PaymentOrder(
        id,
        operationNumber,
        customerId,
        debtorAccount,
        creditorCci,
        amount,
        description,
        processingDate,
        now,
        PaymentStatus.RECEIVED,
        null);
  }

  /**
   * Moves the order to its next status.
   *
   * @throws BusinessException {@link PaymentErrors#TRANSITION_NOT_ALLOWED}
   */
  public void moveTo(PaymentStatus next, @Nullable String reason) {
    if (!status.canMoveTo(next)) {
      throw new BusinessException(
          PaymentErrors.TRANSITION_NOT_ALLOWED,
          status + " -> " + next,
          Map.of("from", status.name(), "to", next.name()));
    }
    this.status = next;
    this.statusReason = reason;
  }

  public String id() {
    return id;
  }

  public String operationNumber() {
    return operationNumber;
  }

  public String customerId() {
    return customerId;
  }

  public String debtorAccount() {
    return debtorAccount;
  }

  public String creditorCci() {
    return creditorCci;
  }

  /** Creditor account showing only its last four digits. */
  public String maskedCreditorCci() {
    return "*".repeat(Math.max(0, creditorCci.length() - 4))
        + creditorCci.substring(Math.max(0, creditorCci.length() - 4));
  }

  public Money amount() {
    return amount;
  }

  public @Nullable String description() {
    return description;
  }

  public LocalDate processingDate() {
    return processingDate;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public PaymentStatus status() {
    return status;
  }

  public @Nullable String statusReason() {
    return statusReason;
  }
}
