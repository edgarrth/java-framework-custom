package pe.axiz.spring.foundation.lifecycle;

import java.time.Duration;

/**
 * Diagnóstico de ciclo de vida de solo lectura compartido por los workers y el cableado de salud.
 */
public interface WorkerStatus {
  /** Estado inmutable del grupo sin tareas, mensajes de excepción ni payloads sensibles. */
  record Snapshot(String name, WorkerLifecycle.State state, int expected, int alive) {}

  Snapshot workerSnapshot();

  Duration shutdownGrace();
}
