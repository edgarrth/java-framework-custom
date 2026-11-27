package pe.axiz.core.saga;

import java.util.Objects;

/**
 * Lo que dice un participante sobre un paso cuyo resultado era incierto (timeout, respuesta
 * perdida, error de servidor): el efecto se aplicó, no se aplicó o nadie lo sabe aún.
 *
 * @param <C> tipo del contexto de la saga
 */
public sealed interface StepCheck<C>
    permits StepCheck.Applied, StepCheck.NotApplied, StepCheck.Rejected, StepCheck.Unknown {

  /**
   * El efecto existe; la saga continúa con este contexto (por ejemplo, con el id de movimiento que
   * informó el participante).
   *
   * @param context contexto después del paso
   */
  record Applied<C>(C context) implements StepCheck<C> {
    public Applied {
      Objects.requireNonNull(context);
    }
  }

  /**
   * Solo una observación de ausencia. Las llamadas pendientes aún pueden aplicarse; la recuperación
   * permanece IN_DOUBT.
   */
  record NotApplied<C>() implements StepCheck<C> {}

  /**
   * El participante rechazó o canceló de forma durable la clave de idempotencia de esta operación y
   * garantiza que ningún intento pendiente o futuro con esa clave puede aplicarse. Un 404 o una
   * lectura sin resultado no es suficiente.
   */
  record Rejected<C>() implements StepCheck<C> {}

  static <C> StepCheck<C> rejected() {
    return new Rejected<>();
  }

  /**
   * Sigue siendo incierto: la saga permanece IN_DOUBT y la recuperación vuelve a consultar más
   * tarde.
   */
  record Unknown<C>() implements StepCheck<C> {}

  static <C> StepCheck<C> applied(C context) {
    return new Applied<>(context);
  }

  static <C> StepCheck<C> notApplied() {
    return new NotApplied<>();
  }

  static <C> StepCheck<C> unknown() {
    return new Unknown<>();
  }
}
