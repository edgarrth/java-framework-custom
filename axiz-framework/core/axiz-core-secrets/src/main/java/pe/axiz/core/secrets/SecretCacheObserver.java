package pe.axiz.core.secrets;

import java.time.Duration;

/**
 * Bounded cache measurements: callbacks receive no secret identifier or secret value.
 * Implementations must return promptly without blocking; failures cannot change cache results.
 */
public interface SecretCacheObserver {
  SecretCacheObserver NONE = new SecretCacheObserver() {};

  /** Result of an actual delegated load, distinct from a cache lookup miss. */
  enum LoadOutcome {
    FOUND,
    ABSENT,
    FAILED
  }

  default void hit() {}

  default void miss() {}

  default void loaded(LoadOutcome outcome, Duration elapsed) {}
}
