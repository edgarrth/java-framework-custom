package pe.axiz.core.carddata;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Errores estables; ningún dato de tarjeta enviado aparece en un mensaje de error. */
public final class CardDataErrors {
  private CardDataErrors() {}

  public static final ErrorDefinition INVALID_PAN =
      ErrorDefinition.of("AXZ-CARD-0001", ErrorCategory.BAD_REQUEST, "Invalid card number");
  public static final ErrorDefinition INVALID_CODE =
      ErrorDefinition.of(
          "AXZ-CARD-0002", ErrorCategory.BAD_REQUEST, "Invalid card authentication data");
  public static final ErrorDefinition INVALID_EXPIRY =
      ErrorDefinition.of("AXZ-CARD-0003", ErrorCategory.BAD_REQUEST, "Invalid card expiry");
}
