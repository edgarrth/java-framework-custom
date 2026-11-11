package pe.axiz.payments.orders.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Error catalog of payment-orders (codes {@code PAYMENTS-PAYMENTORDERS-NNNN}). Detail messages are
 * the {@code error.<code>} keys of the message bundles; the ConfigMap payment-orders-errors can
 * override titles per environment.
 */
public final class PaymentErrors {

  /** The amount exceeds what the customer can still pay today in that currency. */
  public static final ErrorDefinition DAILY_LIMIT_EXCEEDED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0001", ErrorCategory.UNPROCESSABLE, "Daily limit exceeded");

  /** The creditor account is not a valid CCI. */
  public static final ErrorDefinition INVALID_CREDITOR_ACCOUNT =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0002", ErrorCategory.UNPROCESSABLE, "Invalid creditor account");

  /** The order cannot move to the requested status. */
  public static final ErrorDefinition TRANSITION_NOT_ALLOWED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0003", ErrorCategory.CONFLICT, "Status change not allowed");

  /** The currency is not operated. */
  public static final ErrorDefinition UNSUPPORTED_CURRENCY =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0004", ErrorCategory.UNPROCESSABLE, "Currency not supported");

  /** Same amount to the same creditor on the same day: probably a duplicate. */
  public static final ErrorDefinition POSSIBLE_DUPLICATE =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0005", ErrorCategory.CONFLICT, "Possible duplicate payment");

  /** The request key was already used by this customer for a different order. */
  public static final ErrorDefinition REQUEST_KEY_REUSED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0006",
          ErrorCategory.UNPROCESSABLE,
          "Request key reused with another order");

  /** Another request with the same key was registered at the same time: retry to get it. */
  public static final ErrorDefinition CONCURRENT_REQUEST =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0007",
          ErrorCategory.CONFLICT,
          "Same request registered concurrently");

  private PaymentErrors() {}
}
