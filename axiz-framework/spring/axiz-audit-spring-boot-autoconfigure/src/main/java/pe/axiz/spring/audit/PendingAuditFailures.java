package pe.axiz.spring.audit;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Los límites anidados difieren la persistencia de fallos hasta que la transacción de negocio
 * externa haya terminado.
 */
final class PendingAuditFailures {
  private static final ThreadLocal<@Nullable State> CURRENT = new ThreadLocal<>();

  private PendingAuditFailures() {}

  static Scope open() {
    State state = CURRENT.get();
    if (state == null) {
      state = new State();
      CURRENT.set(state);
    }
    state.depth++;
    return new Scope(state);
  }

  static @Nullable State current() {
    return CURRENT.get();
  }

  static final class State {
    int depth;
    final List<Runnable> failures = new ArrayList<>();

    synchronized boolean add(Runnable failure) {
      if (failures.size() >= 1024) return false;
      failures.add(failure);
      return true;
    }

    void drain() {
      List<Runnable> ready;
      synchronized (this) {
        ready = List.copyOf(failures);
        failures.clear();
      }
      for (Runnable failure : ready) failure.run();
    }
  }

  static final class Scope implements AutoCloseable {
    private final State state;
    private boolean closed;

    Scope(State state) {
      this.state = state;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      if (--state.depth == 0) {
        try {
          state.drain();
        } finally {
          CURRENT.remove();
        }
      }
    }
  }
}
