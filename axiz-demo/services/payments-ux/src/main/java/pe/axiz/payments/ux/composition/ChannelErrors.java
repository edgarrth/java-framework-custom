package pe.axiz.payments.ux.composition;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Errores de la API de canales (códigos {@code PAYMENTS-PAYMENTSUX-NNNN}). Los errores de negocio
 * aguas abajo se traducen a estos, de modo que los canales nunca dependen de códigos internos de
 * los servicios.
 */
public final class ChannelErrors {

  /** La moneda del pago difiere de la moneda de la cuenta del beneficiario. */
  public static final ErrorDefinition CURRENCY_MISMATCH =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSUX-0001",
          ErrorCategory.UNPROCESSABLE,
          "Currency does not match the beneficiary");

  /** Los pagos en esa moneda no están habilitados para este cliente o canal (feature flag). */
  public static final ErrorDefinition CURRENCY_NOT_AVAILABLE =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSUX-0002", ErrorCategory.UNPROCESSABLE, "Currency not available");

  /** Se excedería el límite diario. */
  public static final ErrorDefinition LIMIT_EXCEEDED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSUX-0003", ErrorCategory.UNPROCESSABLE, "Daily limit exceeded");

  /** Hoy ya se registró un pago con los mismos datos. */
  public static final ErrorDefinition DUPLICATE_PAYMENT =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSUX-0004", ErrorCategory.CONFLICT, "Possible duplicate payment");

  /** Se rechazó la cuenta del beneficiario. */
  public static final ErrorDefinition INVALID_BENEFICIARY =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTSUX-0005",
          ErrorCategory.UNPROCESSABLE,
          "Beneficiary cannot receive payments");

  private ChannelErrors() {}
}
