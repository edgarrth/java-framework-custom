package pe.axiz.payments.bankingcore.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Catálogo de errores de banking-core (códigos {@code PAYMENTS-BANKINGCORE-NNNN}). Los códigos del
 * core y de la red de pagos nunca salen de este servicio: los adaptadores los traducen a estos.
 */
public final class IntegrationErrors {

  /** La cuenta no tiene fondos disponibles suficientes. */
  public static final ErrorDefinition INSUFFICIENT_FUNDS =
      ErrorDefinition.of(
          "PAYMENTS-BANKINGCORE-0001", ErrorCategory.UNPROCESSABLE, "Insufficient funds");

  /** La cuenta está bloqueada o cerrada en el core. */
  public static final ErrorDefinition ACCOUNT_NOT_OPERATIVE =
      ErrorDefinition.of(
          "PAYMENTS-BANKINGCORE-0002", ErrorCategory.UNPROCESSABLE, "Account cannot operate");

  /** La cuenta debitada no pertenece al cliente del pago. */
  public static final ErrorDefinition ACCOUNT_NOT_OWNED =
      ErrorDefinition.of(
          "PAYMENTS-BANKINGCORE-0004",
          ErrorCategory.UNPROCESSABLE,
          "Account does not belong to the customer");

  private IntegrationErrors() {}
}
