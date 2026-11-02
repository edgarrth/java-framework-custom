package pe.axiz.core.sequence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory counters for tests and local runs. */
public final class InMemorySequenceStore implements SequenceStore {

  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

  @Override
  public long increment(String name, String period, int increment) {
    if (increment <= 0) throw new IllegalArgumentException("increment must be positive");
    return counters
        .computeIfAbsent(name + "|" + period, k -> new AtomicLong())
        .updateAndGet(value -> Math.addExact(value, increment));
  }
}
