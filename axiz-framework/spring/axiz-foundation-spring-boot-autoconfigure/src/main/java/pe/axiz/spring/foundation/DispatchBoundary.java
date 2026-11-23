package pe.axiz.spring.foundation;

/**
 * Abre trabajo de infraestructura cuya finalización debe ejecutarse después de que terminen las
 * transacciones de manejo de mensajes.
 */
@FunctionalInterface
public interface DispatchBoundary {
  Scope open();

  /** La finalización se ejecuta una vez que el dispatcher contenedor ha liberado su transacción. */
  @FunctionalInterface
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
