package pe.axiz.adapter.tokenization.redis;

import java.util.Objects;
import java.util.UUID;

/**
 * Instancia de despliegue y encarnación de proceso propiedad del servidor; nunca la suministra una
 * petición.
 */
public record TokenBudgetOwner(String instance, UUID incarnation) {
  private static final TokenBudgetOwner PROCESS =
      new TokenBudgetOwner("process-" + UUID.randomUUID(), UUID.randomUUID());

  public TokenBudgetOwner {
    if (instance == null || !instance.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
      throw new IllegalArgumentException("Invalid token budget instance identity");
    Objects.requireNonNull(incarnation);
  }

  /**
   * Identidad por defecto compartida por todos los presupuestos de esta JVM; el mapeo del
   * despliegue se registra en log al arrancar.
   */
  public static TokenBudgetOwner process() {
    return PROCESS;
  }
}
