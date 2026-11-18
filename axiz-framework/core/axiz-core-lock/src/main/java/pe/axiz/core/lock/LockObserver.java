package pe.axiz.core.lock;

/**
 * Receives operational failures; RuntimeException from an observer cannot change business results.
 */
@FunctionalInterface
public interface LockObserver {
  void releaseFailed(String resource, RuntimeException failure);

  LockObserver NONE = (resource, failure) -> {};
}
