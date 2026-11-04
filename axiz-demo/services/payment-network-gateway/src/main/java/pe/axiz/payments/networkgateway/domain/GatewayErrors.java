package pe.axiz.payments.networkgateway.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Stable network error contract retained across the extraction from banking core. */
public final class GatewayErrors {
  /** The legacy public code remains unchanged for callers during migration. */
  public static final ErrorDefinition TRANSFER_REJECTED =
      ErrorDefinition.of(
          "PAYMENTS-COREINTEGRATION-0003", ErrorCategory.UNPROCESSABLE, "Transfer rejected");

  private GatewayErrors() {}
}
