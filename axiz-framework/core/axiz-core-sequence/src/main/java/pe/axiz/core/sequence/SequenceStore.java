package pe.axiz.core.sequence;

/** Contadores atómicos por nombre y periodo. */
public interface SequenceStore {

  /**
   * Suma de forma atómica {@code increment} al contador y devuelve el valor nuevo (el último valor
   * del bloque asignado {@code [value - increment + 1, value]}). La reserva debe confirmarse con
   * independencia de la transacción de negocio de quien llama antes de retornar: una reserva con
   * rollback nunca debe quedar disponible en la caché de un generador.
   *
   * @param name nombre de la secuencia
   * @param period clave del periodo ({@code ""} cuando la secuencia nunca se reinicia)
   * @param increment tamaño del bloque
   * @return el valor nuevo del contador
   */
  long increment(String name, String period, int increment);
}
