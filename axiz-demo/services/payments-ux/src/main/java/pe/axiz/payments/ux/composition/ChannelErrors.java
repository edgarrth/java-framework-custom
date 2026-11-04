package pe.axiz.payments.ux.composition;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Errors of the channel API (codes {@code PAYMENTS-PAYMENTSEXPERIENCE-NNNN}). Downstream business
 * errors are translated to these, so channels never depend on internal service codes.
 */
public final class ChannelErrors {

  /** The payment currency differs from the beneficiary account currency. */
  public static final ErrorDefinition CURRENCY_MISMATCH =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSEXPERIENCE-0001",
          ErrorCategory.UNPROCESSABLE,
          "Currency does not match the beneficiary");

  /** Payments in that currency are not enabled for this customer or channel (feature flag). */
  public static final ErrorDefinition CURRENCY_NOT_AVAILABLE =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSEXPERIENCE-0002",
          ErrorCategory.UNPROCESSABLE,
          "Currency not available");

  /** The daily limit would be exceeded. */
  public static final ErrorDefinition LIMIT_EXCEEDED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSEXPERIENCE-0003", ErrorCategory.UNPROCESSABLE, "Daily limit exceeded");

  /** A payment with the same data was already registered today. */
  public static final ErrorDefinition DUPLICATE_PAYMENT =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSEXPERIENCE-0004", ErrorCategory.CONFLICT, "Possible duplicate payment");

  /** The beneficiary account was rejected. */
  public static final ErrorDefinition INVALID_BENEFICIARY =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSEXPERIENCE-0005",
          ErrorCategory.UNPROCESSABLE,
          "Beneficiary cannot receive payments");

  private ChannelErrors() {}
}
