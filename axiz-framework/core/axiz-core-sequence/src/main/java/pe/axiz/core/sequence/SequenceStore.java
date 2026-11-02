package pe.axiz.core.sequence;

/** Atomic counters per name and period. */
public interface SequenceStore {

  /**
   * Atomically adds {@code increment} to the counter and returns the new value (the last value of
   * the allocated block {@code [value - increment + 1, value]}). The reservation must be committed
   * independently of a caller's business transaction before returning: a rolled-back reservation
   * must never remain available in a generator's cache.
   *
   * @param name sequence name
   * @param period period key ({@code ""} when the sequence never resets)
   * @param increment block size
   * @return the new counter value
   */
  long increment(String name, String period, int increment);
}
