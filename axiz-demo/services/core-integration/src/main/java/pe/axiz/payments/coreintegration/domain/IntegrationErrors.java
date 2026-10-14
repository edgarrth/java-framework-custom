package pe.axiz.payments.coreintegration.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Error catalog of core-integration (codes {@code PAYMENTS-COREINTEGRATION-NNNN}). Codes of the
 * core and the payment network never leave this service: adapters translate them to these.
 */
public final class IntegrationErrors {

  /** The account does not have enough available funds. */
  public static final ErrorDefinition INSUFFICIENT_FUNDS =
      ErrorDefinition.of(
          "PAYMENTS-COREINTEGRATION-0001", ErrorCategory.UNPROCESSABLE, "Insufficient funds");

  /** The account is blocked or closed in the core. */
  public static final ErrorDefinition ACCOUNT_NOT_OPERATIVE =
      ErrorDefinition.of(
          "PAYMENTS-COREINTEGRATION-0002", ErrorCategory.UNPROCESSABLE, "Account cannot operate");

  /** The payment network rejected the transfer. */
  public static final ErrorDefinition TRANSFER_REJECTED =
      ErrorDefinition.of(
          "PAYMENTS-COREINTEGRATION-0003", ErrorCategory.UNPROCESSABLE, "Transfer rejected");

  /** The debited account does not belong to the customer of the payment. */
  public static final ErrorDefinition ACCOUNT_NOT_OWNED =
      ErrorDefinition.of(
          "PAYMENTS-COREINTEGRATION-0004",
          ErrorCategory.UNPROCESSABLE,
          "Account does not belong to the customer");

  private IntegrationErrors() {}
}
