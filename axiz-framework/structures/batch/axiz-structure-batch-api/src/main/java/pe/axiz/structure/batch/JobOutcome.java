package pe.axiz.structure.batch;

import java.time.Duration;

/**
 * Resumen de un job terminado, usado para métricas, auditoría y el código de salida del CronJob.
 *
 * @param job nombre del job
 * @param status estado final ({@code COMPLETED}, {@code FAILED}, etc.)
 * @param read registros leídos
 * @param written registros escritos
 * @param skipped registros omitidos
 * @param duration tiempo transcurrido
 */
public record JobOutcome(
    String job, String status, long read, long written, long skipped, Duration duration) {

  public boolean successful() {
    return "COMPLETED".equals(status);
  }

  /**
   * Código de salida del proceso: 0 si tiene éxito y 1 en otro caso (Kubernetes marca la ejecución
   * del CronJob como fallida).
   */
  public int exitCode() {
    return successful() ? 0 : 1;
  }
}
