package pe.axiz.core.money;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Errores de la capacidad de dinero. */
public final class MoneyErrors {

  public static final ErrorDefinition CURRENCY_MISMATCH =
      ErrorDefinition.of("AXZ-MONEY-0001", ErrorCategory.UNPROCESSABLE, "Currency mismatch");
  public static final ErrorDefinition INVALID_AMOUNT =
      ErrorDefinition.of("AXZ-MONEY-0002", ErrorCategory.BAD_REQUEST, "Invalid amount");
  public static final ErrorDefinition RATE_NOT_AVAILABLE =
      ErrorDefinition.of(
          "AXZ-MONEY-0003", ErrorCategory.SERVICE_UNAVAILABLE, "Exchange rate not available");

  private MoneyErrors() {}
}
