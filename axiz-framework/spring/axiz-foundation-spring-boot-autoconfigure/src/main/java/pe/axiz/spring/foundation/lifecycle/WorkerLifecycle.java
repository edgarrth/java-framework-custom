package pe.axiz.spring.foundation.lifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Posee un grupo reiniciable de workers. El inicio es idempotente, la detención rechaza trabajo
 * nuevo y los callbacks solo se ejecutan después de que todos los workers y la limpieza asociada
 * hayan terminado. Un timeout de apagado interrumpe los workers, pero nunca finge que una E/S no
 * cooperativa ha terminado.
 */
public final class WorkerLifecycle implements SmartLifecycle, WorkerStatus {
  /** Estado operativo; FAILED conserva la propiedad hasta que se concilien los recursos. */
  public enum State {
    STOPPED,
    RUNNING,
    STOPPING,
    FAILED
  }

  private final String name;
  private final Duration grace;
  private final Supplier<List<Runnable>> tasks;
  private final Runnable cleanup;
  private volatile @Nullable Run current;

  public WorkerLifecycle(
      String name, Duration grace, Supplier<List<Runnable>> tasks, Runnable cleanup) {
    this.name = Objects.requireNonNull(name);
    this.grace = Objects.requireNonNull(grace);
    if (grace.isNegative() || grace.compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException("Shutdown grace must be between zero and five minutes");
    this.tasks = Objects.requireNonNull(tasks);
    this.cleanup = Objects.requireNonNull(cleanup);
  }

  @Override
  public synchronized void start() {
    if (current != null) {
      if (current.failed || current.stopping.get())
        throw new IllegalStateException(
            "Previous worker group is stopping or failed; restart refused");
      return;
    }
    Run run = new Run();
    current = run;
    try {
      List<Runnable> batch = List.copyOf(tasks.get());
      for (int i = 0; i < batch.size(); i++)
        run.workers.add(
            Thread.ofVirtual().name(name + "-" + i).unstarted(guarded(run, batch.get(i))));
      run.workers.forEach(Thread::start);
    } catch (RuntimeException | Error failure) {
      run.stopping.set(true);
      run.workers.forEach(Thread::interrupt);
      throw failure;
    } finally {
      Thread.ofVirtual().name(name + "-termination").start(() -> terminated(run));
    }
  }

  private Runnable guarded(Run run, Runnable task) {
    return () -> {
      try {
        task.run();
      } catch (RuntimeException | Error failure) {
        run.failed = true;
        run.stopping.set(true);
        LoggerFactory.getLogger(WorkerLifecycle.class)
            .error(
                "Worker terminated unexpectedly; group={}, failureType={}",
                name,
                failure.getClass().getSimpleName());
        run.workers.forEach(Thread::interrupt);
      }
    };
  }

  @Override
  public synchronized Snapshot workerSnapshot() {
    Run run = current;
    int expected = run == null ? 0 : run.workers.size();
    int alive = run == null ? 0 : (int) run.workers.stream().filter(Thread::isAlive).count();
    return new Snapshot(name, state(), expected, alive);
  }

  @Override
  public Duration shutdownGrace() {
    return grace;
  }

  /** Condición del bucle para los workers propios; es false en cuanto se solicita la detención. */
  public boolean accepting() {
    Run run = current;
    return run != null && !run.failed && !run.stopping.get();
  }

  /**
   * Se usa para readiness y salud: FAILED requiere la conciliación de un operador, nunca un
   * reinicio silencioso.
   */
  public State state() {
    Run run = current;
    if (run == null) return State.STOPPED;
    if (run.failed) return State.FAILED;
    return run.stopping.get() ? State.STOPPING : State.RUNNING;
  }

  @Override
  public boolean isRunning() {
    Run run = current;
    return run != null && (!run.done.isDone() || run.done.isCompletedExceptionally());
  }

  @Override
  public void stop() {
    Run run = requestStop();
    if (run == null) return;
    try {
      run.done.get(grace.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Worker shutdown interrupted", interrupted);
    } catch (TimeoutException timeout) {
      throw new IllegalStateException(
          "Workers have not terminated; shutdown remains in progress", timeout);
    } catch (ExecutionException failure) {
      throw new IllegalStateException("Worker cleanup failed", failure.getCause());
    }
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored") // Los fallos se registran dentro del callback.
  public void stop(Runnable callback) {
    Objects.requireNonNull(callback);
    Run run = requestStop();
    if (run == null) callback.run();
    else
      run.done.whenComplete(
          (ignored, failure) -> {
            if (failure != null) return; // Una limpieza incompleta no es un ciclo de vida detenido.
            try {
              callback.run();
            } catch (RuntimeException callbackFailure) {
              LoggerFactory.getLogger(WorkerLifecycle.class)
                  .warn(
                      "Worker stop callback failed; failureType={}",
                      callbackFailure.getClass().getSimpleName());
            }
          });
  }

  private synchronized @Nullable Run requestStop() {
    Run run = current;
    if (run == null || !run.stopping.compareAndSet(false, true)) return run;
    Thread.ofVirtual()
        .name(name + "-stop")
        .start(
            () -> {
              try {
                run.done.get(grace.toNanos(), TimeUnit.NANOSECONDS);
              } catch (TimeoutException timeout) {
                run.workers.forEach(Thread::interrupt);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                run.workers.forEach(Thread::interrupt);
              } catch (ExecutionException failure) {
                /* La terminación ya informó el fallo de limpieza. */
              }
            });
    return run;
  }

  private void terminated(Run run) {
    Throwable failure = null;
    try {
      for (Thread worker : run.workers) worker.join();
      cleanup.run();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failure = interrupted;
      run.failed = true;
    } catch (RuntimeException | Error cleanupFailure) {
      failure = cleanupFailure;
      run.failed = true;
      LoggerFactory.getLogger(WorkerLifecycle.class)
          .error(
              "Worker cleanup failed; failureType={}", cleanupFailure.getClass().getSimpleName());
    }
    synchronized (this) {
      if (failure == null && !run.failed && current == run) current = null;
    }
    if (failure == null) run.done.complete(null);
    else run.done.completeExceptionally(failure);
  }

  private static final class Run {
    volatile boolean failed;
    final List<Thread> workers = new ArrayList<>();
    final AtomicBoolean stopping = new AtomicBoolean();
    final CompletableFuture<Void> done = new CompletableFuture<>();
  }
}
