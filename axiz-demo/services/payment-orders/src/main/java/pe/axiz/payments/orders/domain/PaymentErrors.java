package pe.axiz.payments.orders.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/**
 * Catálogo de errores de payment-orders (códigos {@code PAYMENTS-PAYMENTORDERS-NNNN}). Los mensajes
 * de detalle son las claves {@code error.<code>} de los paquetes de mensajes; el ConfigMap
 * payment-orders-errors puede sobrescribir los títulos por entorno.
 */
public final class PaymentErrors {

  /** El monto excede lo que el cliente todavía puede pagar hoy en esa moneda. */
  public static final ErrorDefinition DAILY_LIMIT_EXCEEDED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0001", ErrorCategory.UNPROCESSABLE, "Daily limit exceeded");

  /** La cuenta del acreedor no es un CCI válido. */
  public static final ErrorDefinition INVALID_CREDITOR_ACCOUNT =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0002", ErrorCategory.UNPROCESSABLE, "Invalid creditor account");

  /** La orden no puede pasar al estado solicitado. */
  public static final ErrorDefinition TRANSITION_NOT_ALLOWED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0003", ErrorCategory.CONFLICT, "Status change not allowed");

  /** La moneda no se opera. */
  public static final ErrorDefinition UNSUPPORTED_CURRENCY =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0004", ErrorCategory.UNPROCESSABLE, "Currency not supported");

  /** Mismo monto al mismo acreedor el mismo día: probablemente un duplicado. */
  public static final ErrorDefinition POSSIBLE_DUPLICATE =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0005", ErrorCategory.CONFLICT, "Possible duplicate payment");

  /** Este cliente ya usó la clave de solicitud para una orden distinta. */
  public static final ErrorDefinition REQUEST_KEY_REUSED =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0006",
          ErrorCategory.UNPROCESSABLE,
          "Request key reused with another order");

  /**
   * Otra solicitud con la misma clave se registró al mismo tiempo: se debe reintentar para
   * obtenerla.
   */
  public static final ErrorDefinition CONCURRENT_REQUEST =
      ErrorDefinition.of(
          "PAYMENTS-PAYMENTORDERS-0007",
          ErrorCategory.CONFLICT,
          "Same request registered concurrently");

  private PaymentErrors() {}
}
