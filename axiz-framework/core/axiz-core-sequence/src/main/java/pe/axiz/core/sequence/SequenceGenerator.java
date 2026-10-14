package pe.axiz.core.sequence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Formatted, gap-tolerant sequence. Values are allocated from the store in blocks of {@code
 * blockSize} to reduce round trips (block 1 means no gaps unless a transaction fails). Thread-safe;
 * no state is shared between generators.
 */
public final class SequenceGenerator {

  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,62}$");

  private final String name;
  private final SequenceFormat format;
  private final SequenceStore store;
  private final int blockSize;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private String currentPeriod = "\u0000";
  private long next;
  private long last = -1;

  public SequenceGenerator(
      String name, SequenceFormat format, SequenceStore store, int blockSize, Clock clock) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid sequence name " + name);
    }
    if (blockSize < 1 || blockSize > 10_000) {
      throw new IllegalArgumentException("blockSize must be between 1 and 10000");
    }
    this.name = name;
    this.format = Objects.requireNonNull(format);
    this.store = Objects.requireNonNull(store);
    this.blockSize = blockSize;
    this.clock = Objects.requireNonNull(clock);
  }

  /** Next raw value for the current period. */
  public long nextValue() {
    String period = format.reset().period(LocalDate.now(clock.withZone(format.zone())));
    lock.lock();
    try {
      if (!period.equals(currentPeriod) || next > last) {
        last = store.increment(name, period, blockSize);
        next = last - blockSize + 1;
        currentPeriod = period;
      }
      return next++;
    } finally {
      lock.unlock();
    }
  }

  /** Next formatted value, e.g. {@code OP-20260131-000123}. */
  public String next() {
    String period = format.reset().period(LocalDate.now(clock.withZone(format.zone())));
    long value = nextValue();
    return format.render(period, value);
  }
}
