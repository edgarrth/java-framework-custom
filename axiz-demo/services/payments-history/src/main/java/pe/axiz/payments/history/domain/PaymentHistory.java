package pe.axiz.payments.history.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;

/**
 * Estado proyectado de un pago para su cliente. Los eventos llegan al menos una vez y, entre tipos
 * de evento, posiblemente desordenados: aplicar un evento es idempotente y un estado más antiguo
 * nunca reemplaza a uno más reciente ({@code lastChangeAt} es el momento del evento, no de la
 * proyección). Un cambio de estado recibido previo al registro crea una entrada parcial que el
 * registro completa.
 *
 * @param paymentId id de la orden en payment-orders
 * @param customerId cliente
 * @param operationNumber número de operación, null hasta recibir el registro
 * @param creditorCci cuenta de destino (sensible: se almacena cifrada y se muestra enmascarada)
 * @param amount monto, null hasta recibir el registro
 * @param processingDate fecha de procesamiento, null hasta recibir el registro
 * @param status último estado conocido
 * @param reason motivo del último estado, cuando se indica
 * @param lastChangeAt momento del evento que fijó el estado
 */
public record PaymentHistory(
    String paymentId,
    String customerId,
    @Nullable String operationNumber,
    @Nullable String creditorCci,
    @Nullable Money amount,
    @Nullable LocalDate processingDate,
    PaymentStatus status,
    @Nullable String reason,
    Instant lastChangeAt) {

  public PaymentHistory {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(lastChangeAt, "lastChangeAt");
  }

  /**
   * Detalles de una orden registrada.
   *
   * @param operationNumber número de operación
   * @param creditorCci cuenta de destino
   * @param amount monto
   * @param processingDate fecha de procesamiento
   */
  public record Registration(
      String operationNumber, String creditorCci, Money amount, LocalDate processingDate) {}

  /** Aplica el registro de la orden a la entrada actual (null cuando no existe). */
  public static PaymentHistory registered(
      @Nullable PaymentHistory current,
      String paymentId,
      String customerId,
      Registration registration,
      Instant at) {
    if (current == null) {
      return new PaymentHistory(
          paymentId,
          customerId,
          registration.operationNumber(),
          registration.creditorCci(),
          registration.amount(),
          registration.processingDate(),
          PaymentStatus.RECEIVED,
          null,
          at);
    }
    // llegó primero un cambio de estado: se conserva y se completan los detalles
    return new PaymentHistory(
        current.paymentId,
        current.customerId,
        registration.operationNumber(),
        registration.creditorCci(),
        registration.amount(),
        registration.processingDate(),
        current.status,
        current.reason,
        current.lastChangeAt);
  }

  /** Aplica un cambio de estado a la entrada actual (null cuando no existe). */
  public static PaymentHistory statusChanged(
      @Nullable PaymentHistory current,
      String paymentId,
      String customerId,
      PaymentStatus status,
      @Nullable String reason,
      Instant at) {
    if (current == null) {
      return new PaymentHistory(paymentId, customerId, null, null, null, null, status, reason, at);
    }
    if (at.isBefore(current.lastChangeAt) || (current.status.isFinal() && !status.isFinal())) {
      return current; // desactualizado o reentregado: nunca retrocede
    }
    return new PaymentHistory(
        current.paymentId,
        current.customerId,
        current.operationNumber,
        current.creditorCci,
        current.amount,
        current.processingDate,
        status,
        reason,
        at);
  }

  /** Cuenta de destino con todos los dígitos ocultos salvo los últimos cuatro. */
  public @Nullable String maskedCreditorCci() {
    if (creditorCci == null) {
      return null;
    }
    int visible = Math.min(4, creditorCci.length());
    return "*".repeat(creditorCci.length() - visible)
        + creditorCci.substring(creditorCci.length() - visible);
  }

  @Override
  public String toString() {
    return "PaymentHistory[" + paymentId + ", " + status + "]";
  }
}
