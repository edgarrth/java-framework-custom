package pe.axiz.spring.foundation;

/** Opens infrastructure work whose completion must run after message handling transactions exit. */
@FunctionalInterface
public interface DispatchBoundary {
  Scope open();

  /** Completion runs once the enclosing dispatcher has released its transaction. */
  @FunctionalInterface
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
