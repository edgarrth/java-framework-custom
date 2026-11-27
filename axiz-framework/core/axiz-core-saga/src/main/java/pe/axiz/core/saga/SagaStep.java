package pe.axiz.core.saga;

/**
 * Paso de una saga: una acción, su compensación y, para efectos remotos, cómo verificar un
 * resultado incierto. La acción y la compensación deben ser idempotentes porque una saga reanudada
 * puede repetir el último paso.
 *
 * @param <C> tipo del contexto de la saga
 */
public interface SagaStep<C> {

  String name();

  /** Ejecuta el paso y devuelve el contexto (posiblemente enriquecido). */
  C execute(C context) throws Exception;

  /**
   * Ejecuta con un fence monotónicamente creciente. Los participantes que soportan fencing deben
   * rechazar de forma atómica los fences anteriores y ligar los reintentos a la clave de
   * idempotencia estable de saga y paso. La implementación por defecto requiere que la operación
   * execute original sea idempotente.
   */
  default C execute(C context, SagaStepExecution execution) throws Exception {
    return execute(context);
  }

  /** Deshace el paso después de que falló un paso posterior. */
  void compensate(C context) throws Exception;

  default void compensate(C context, SagaStepExecution execution) throws Exception {
    compensate(context);
  }

  default StepCheck<C> verify(C context, SagaStepExecution execution) throws Exception {
    return verify(context);
  }

  /**
   * Consulta al participante si existe el efecto de {@link #execute}, después de que falló sin un
   * rechazo definitivo (timeout, respuesta perdida, error de servidor). Normalmente es una consulta
   * de estado por la clave de idempotencia del paso, o la misma llamada idempotente repetida con
   * esa clave. La respuesta por defecto es {@link StepCheck#unknown()}: la saga pasa a IN_DOUBT y
   * no se compensa nada hasta que se conozca el resultado (o decida un operador).
   */
  default StepCheck<C> verify(C context) throws Exception {
    return StepCheck.unknown();
  }
}
