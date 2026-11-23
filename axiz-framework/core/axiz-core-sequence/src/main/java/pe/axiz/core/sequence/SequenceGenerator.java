package pe.axiz.core.sequence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Secuencia formateada y tolerante a huecos. Los valores se asignan desde el almacén en bloques de
 * {@code blockSize} para reducir los viajes de ida y vuelta (un bloque de 1 significa sin huecos
 * salvo que falle una transacción). Thread-safe; no se comparte estado entre generadores.
 */
public final class SequenceGenerator {

  private final String name;
  private final SequenceFormat format;
  private final SequenceStore store;
  private final int blockSize;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private String currentPeriod = "\u0000";
  private long next;
  private int remaining;

  public SequenceGenerator(
      String name, SequenceFormat format, SequenceStore store, int blockSize, Clock clock) {
    SequenceAllocation.validate(name, blockSize);
    this.name = name;
    this.format = Objects.requireNonNull(format);
    this.store = Objects.requireNonNull(store);
    this.blockSize = blockSize;
    this.clock = Objects.requireNonNull(clock);
  }

  /** Siguiente valor en bruto para el periodo actual. */
  public long nextValue() {
    String period = format.reset().period(LocalDate.now(clock.withZone(format.zone())));
    return nextValue(period);
  }

  private long nextValue(String period) {
    lock.lock();
    try {
      if (!period.equals(currentPeriod) || remaining == 0) {
        long last = store.increment(name, period, blockSize);
        next = SequenceAllocation.first(last, blockSize);
        remaining = blockSize;
        currentPeriod = period;
      }
      long value = next;
      if (--remaining > 0) next++;
      return value;
    } finally {
      lock.unlock();
    }
  }

  /** Siguiente valor formateado, por ejemplo {@code OP-20260131-000123}. */
  public String next() {
    String period = format.reset().period(LocalDate.now(clock.withZone(format.zone())));
    long value = nextValue(period);
    return format.render(period, value);
  }
}
