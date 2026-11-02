package pe.axiz.structure.hexagonal;

import java.util.function.Supplier;

/** Runs work in a transaction (implemented by the Spring starter with a transaction template). */
public interface UseCaseTransaction {

  <T> T execute(boolean readOnly, Supplier<T> work);

  /** No transaction: for tests and stateless use cases. */
  static UseCaseTransaction none() {
    return new UseCaseTransaction() {
      @Override
      public <T> T execute(boolean readOnly, Supplier<T> work) {
        return work.get();
      }
    };
  }
}
