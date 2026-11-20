package pe.axiz.spring.foundation.lifecycle;

import java.time.Duration;

/** Read-only lifecycle diagnostics shared by workers and health wiring. */
public interface WorkerStatus {
  /** Immutable group status without tasks, exception messages or sensitive payloads. */
  record Snapshot(String name, WorkerLifecycle.State state, int expected, int alive) {}

  Snapshot workerSnapshot();

  Duration shutdownGrace();
}
