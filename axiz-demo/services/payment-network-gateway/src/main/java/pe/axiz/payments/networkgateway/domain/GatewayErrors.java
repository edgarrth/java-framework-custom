package pe.axiz.payments.networkgateway.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Catálogo de errores de payment-network-gateway (códigos {@code PAYMENTS-NETWORKGATEWAY-NNNN}).
 */
public final class GatewayErrors {
  /** La red interbancaria rechazó la transferencia. */
  public static final ErrorDefinition TRANSFER_REJECTED =
      ErrorDefinition.of(
          "PAYMENTS-NETWORKGATEWAY-0001", ErrorCategory.UNPROCESSABLE, "Transfer rejected");

  private GatewayErrors() {}
}
