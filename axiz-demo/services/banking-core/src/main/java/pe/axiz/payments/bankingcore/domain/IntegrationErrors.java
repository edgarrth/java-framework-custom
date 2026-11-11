package pe.axiz.payments.bankingcore.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Error catalog of banking-core (codes {@code PAYMENTS-BANKINGCORE-NNNN}). Codes of the core and
 * the payment network never leave this service: adapters translate them to these.
 */
public final class IntegrationErrors {

  /** The account does not have enough available funds. */
  public static final ErrorDefinition INSUFFICIENT_FUNDS =
      ErrorDefinition.of(
          "PAYMENTS-BANKINGCORE-0001", ErrorCategory.UNPROCESSABLE, "Insufficient funds");

  /** The account is blocked or closed in the core. */
  public static final ErrorDefinition ACCOUNT_NOT_OPERATIVE =
      ErrorDefinition.of(
          "PAYMENTS-BANKINGCORE-0002", ErrorCategory.UNPROCESSABLE, "Account cannot operate");

  /** The debited account does not belong to the customer of the payment. */
  public static final ErrorDefinition ACCOUNT_NOT_OWNED =
      ErrorDefinition.of(
          "PAYMENTS-BANKINGCORE-0004",
          ErrorCategory.UNPROCESSABLE,
          "Account does not belong to the customer");

  private IntegrationErrors() {}
}
