package pe.axiz.structure.hexagonal;

import java.util.function.Supplier;

/**
 * Ejecuta trabajo en una transacción (lo implementa el starter de Spring con un transaction
 * template).
 */
public interface UseCaseTransaction {

  <T> T execute(boolean readOnly, Supplier<T> work);

  /** Sin transacción: para pruebas y casos de uso sin estado. */
  static UseCaseTransaction none() {
    return new UseCaseTransaction() {
      @Override
      public <T> T execute(boolean readOnly, Supplier<T> work) {
        return work.get();
      }
    };
  }
}
