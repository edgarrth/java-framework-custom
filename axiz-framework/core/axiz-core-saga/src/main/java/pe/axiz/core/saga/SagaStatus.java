package pe.axiz.core.saga;

/** Ciclo de vida de la saga. */
public enum SagaStatus {
  RUNNING,
  COMPLETED,
  COMPENSATING,
  COMPENSATED,
  /**
   * Un paso falló con un resultado incierto (el efecto puede existir): no se compensa ni se declara
   * nada hasta que la verificación responda; la recuperación vuelve a consultar.
   */
  IN_DOUBT,
  /** La compensación falló: requiere intervención manual. */
  FAILED;

  public boolean terminal() {
    return this == COMPLETED || this == COMPENSATED || this == FAILED;
  }
}
