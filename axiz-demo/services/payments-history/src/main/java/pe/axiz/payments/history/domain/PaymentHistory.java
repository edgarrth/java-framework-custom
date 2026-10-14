package pe.axiz.payments.history.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;

/**
 * Projected state of one payment for its customer. Events arrive at least once and, across event
 * types, possibly out of order: applying an event is idempotent and an older status never replaces
 * a newer one ({@code lastChangeAt} is the time of the event, not of the projection). A status
 * change seen before the registration creates a partial entry that the registration completes.
 *
 * @param paymentId order id in payments-business
 * @param customerId customer
 * @param operationNumber operation number, null until the registration is seen
 * @param creditorCci destination account (sensitive: stored encrypted, shown masked)
 * @param amount amount, null until the registration is seen
 * @param processingDate processing date, null until the registration is seen
 * @param status latest known status
 * @param reason reason of the latest status, when given
 * @param lastChangeAt time of the event that set the status
 */
public record PaymentHistory(
    String paymentId,
    String customerId,
    @Nullable String operationNumber,
    @Nullable String creditorCci,
    @Nullable Money amount,
    @Nullable LocalDate processingDate,
    PaymentStatus status,
    @Nullable String reason,
    Instant lastChangeAt) {

  public PaymentHistory {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(lastChangeAt, "lastChangeAt");
  }

  /**
   * Details of a registered order.
   *
   * @param operationNumber operation number
   * @param creditorCci destination account
   * @param amount amount
   * @param processingDate processing date
   */
  public record Registration(
      String operationNumber, String creditorCci, Money amount, LocalDate processingDate) {}

  /** Applies the registration of the order to the current entry (null when there is none). */
  public static PaymentHistory registered(
      @Nullable PaymentHistory current,
      String paymentId,
      String customerId,
      Registration registration,
      Instant at) {
    if (current == null) {
      return new PaymentHistory(
          paymentId,
          customerId,
          registration.operationNumber(),
          registration.creditorCci(),
          registration.amount(),
          registration.processingDate(),
          PaymentStatus.RECEIVED,
          null,
          at);
    }
    // a status change came first: keep it, fill in the details
    return new PaymentHistory(
        current.paymentId,
        current.customerId,
        registration.operationNumber(),
        registration.creditorCci(),
        registration.amount(),
        registration.processingDate(),
        current.status,
        current.reason,
        current.lastChangeAt);
  }

  /** Applies a status change to the current entry (null when there is none). */
  public static PaymentHistory statusChanged(
      @Nullable PaymentHistory current,
      String paymentId,
      String customerId,
      PaymentStatus status,
      @Nullable String reason,
      Instant at) {
    if (current == null) {
      return new PaymentHistory(paymentId, customerId, null, null, null, null, status, reason, at);
    }
    if (at.isBefore(current.lastChangeAt) || (current.status.isFinal() && !status.isFinal())) {
      return current; // stale or redelivered: never go back
    }
    return new PaymentHistory(
        current.paymentId,
        current.customerId,
        current.operationNumber,
        current.creditorCci,
        current.amount,
        current.processingDate,
        status,
        reason,
        at);
  }

  /** Destination account with all but the last four digits hidden. */
  public @Nullable String maskedCreditorCci() {
    if (creditorCci == null) {
      return null;
    }
    int visible = Math.min(4, creditorCci.length());
    return "*".repeat(creditorCci.length() - visible)
        + creditorCci.substring(creditorCci.length() - visible);
  }

  @Override
  public String toString() {
    return "PaymentHistory[" + paymentId + ", " + status + "]";
  }
}
