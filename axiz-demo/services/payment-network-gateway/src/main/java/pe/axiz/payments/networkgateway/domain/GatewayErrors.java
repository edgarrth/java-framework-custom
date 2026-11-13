package pe.axiz.payments.networkgateway.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Error catalog of payment-network-gateway (codes {@code PAYMENTS-NETWORKGATEWAY-NNNN}). */
public final class GatewayErrors {
  /** The inter-bank network rejected the transfer. */
  public static final ErrorDefinition TRANSFER_REJECTED =
      ErrorDefinition.of(
          "PAYMENTS-NETWORKGATEWAY-0001", ErrorCategory.UNPROCESSABLE, "Transfer rejected");

  private GatewayErrors() {}
}
