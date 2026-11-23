package pe.axiz.structure.hexagonal;

/**
 * Caso de uso de la aplicación (implementación de un puerto de entrada). Se ejecuta en una
 * transacción; los eventos de dominio lanzados durante su ejecución se publican cuando esa
 * transacción hace commit.
 *
 * @param <I> entrada (un record de comando o consulta)
 * @param <O> salida
 */
@FunctionalInterface
public interface UseCase<I, O> {

  O execute(I input);
}
