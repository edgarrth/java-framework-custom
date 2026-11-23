package pe.axiz.core.lock;

/**
 * Recibe los fallos operativos; una RuntimeException de un observador no puede cambiar los
 * resultados de negocio.
 */
@FunctionalInterface
public interface LockObserver {
  void releaseFailed(String resource, RuntimeException failure);

  LockObserver NONE = (resource, failure) -> {};
}
