package pe.axiz.core.lock;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.concurrent.LeaseTiming;
import pe.axiz.core.errors.BusinessException;

/**
 * Serializa el trabajo sobre un recurso: espera hasta {@code waitTimeout} por el lock, renueva el
 * lease en segundo plano (hilo virtual, cada tercio del lease) mientras corre el trabajo y siempre
 * lo libera. Si no puede obtenerse el lock, quien llama recibe un error de negocio 409.
 *
 * <p>Un lease que se pierde mientras corre el trabajo nunca se informa como éxito: el almacén
 * respondió que otro propietario lo tiene, o no pudo renovarse antes de expirar. En ese momento se
 * interrumpe el hilo que ejecuta el trabajo (cancelación cooperativa: las llamadas bloqueantes
 * terminan con una {@link InterruptedException}), y el trabajo termina en una {@link
 * LockLostException} (503, reintentable) en lugar de devolver su resultado; si el trabajo falló por
 * sí mismo, se lanza su excepción con la pérdida adjunta como suprimida. El trabajo con efectos
 * externos recibe el {@link LockLease} para verificarlo justo antes de cada efecto y para usar su
 * token en escrituras condicionales; ver {@link LockLease} para saber por qué el propio recurso
 * también debe rechazar a un propietario anterior.
 *
 * <p>El lease se cuenta desde antes de cada petición al almacén, con el reloj monotónico, menos un
 * margen por la deriva de reloj entre este proceso y el almacén (1% del lease más 2 ms): el tiempo
 * de espera de la respuesta es tiempo que el almacén ya contó. Una adquisición que responde después
 * de agotado su lease se libera y no se usa (para entonces otro propietario puede tener el
 * recurso); la plantilla sigue intentando hasta {@code waitTimeout}.
 */
public final class LockTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(LockTemplate.class);

  /** Trabajo que recibe su lease. */
  @FunctionalInterface
  public interface LeasedWork<T extends @Nullable Object> {
    T call(LockLease lease) throws Exception;
  }

  private final DistributedLock lock;
  private final Duration pollInterval;
  private final LockObserver observer;

  public LockTemplate(DistributedLock lock, Duration pollInterval, LockObserver observer) {
    this.lock = Objects.requireNonNull(lock);
    this.observer = Objects.requireNonNull(observer);
    this.pollInterval = Objects.requireNonNull(pollInterval);
  }

  /**
   * Ejecuta un trabajo bajo el lock.
   *
   * @throws BusinessException {@link LockErrors#BUSY} cuando el lock no se adquiere a tiempo
   * @throws LockLostException cuando el lease se perdió antes de que el trabajo terminara
   */
  public <T extends @Nullable Object> T withLock(
      String resource, Duration lease, Duration waitTimeout, Callable<T> work) throws Exception {
    return withLock(resource, lease, waitTimeout, held -> work.call());
  }

  /**
   * Ejecuta bajo el lock un trabajo que verifica su lease.
   *
   * @throws BusinessException {@link LockErrors#BUSY} cuando el lock no se adquiere a tiempo
   * @throws LockLostException cuando el lease se perdió antes de que el trabajo terminara
   */
  public <T extends @Nullable Object> T withLock(
      String resource, Duration lease, Duration waitTimeout, LeasedWork<T> work) throws Exception {
    Acquired acquired = acquire(resource, lease, waitTimeout);
    LockHandle handle = acquired.handle();
    Lease held = new Lease(handle, acquired.validUntil(), Thread.currentThread());
    AtomicBoolean done = new AtomicBoolean();
    Thread renewer =
        Thread.ofVirtual()
            .name("axiz-lock-renew-" + resource)
            .start(() -> renewLoop(held, lease, done));
    T result;
    try {
      result = work.call(held);
    } catch (Exception | Error failure) {
      boolean lost = held.finish();
      RuntimeException release = stop(done, renewer, handle);
      if (release != null && release != failure) failure.addSuppressed(release);
      if (lost && failure instanceof InterruptedException) {
        throw held.lost(failure);
      }
      if (lost) {
        failure.addSuppressed(held.lost(null));
      }
      throw failure;
    }
    boolean lost = held.finish();
    RuntimeException release = stop(done, renewer, handle);
    if (lost) {
      LockLostException failure = held.lost(null);
      if (release != null) failure.addSuppressed(release);
      throw failure;
    }
    if (release != null) {
      LOG.warn(
          "Lock release failed after completed work; the lease will expire (failure type {})",
          release.getClass().getSimpleName());
      try {
        observer.releaseFailed(resource, release);
      } catch (RuntimeException ignored) {
        LOG.warn("Lock observer failed to record release failure");
      }
    }
    return result;
  }

  private static @Nullable RuntimeException stop(
      AtomicBoolean done, Thread renewer, LockHandle handle) {
    done.set(true);
    renewer.interrupt();
    try {
      renewer.join(Duration.ofSeconds(1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      handle.release();
      return null;
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  /** Handle y momento (nanos monotónicos) en que su lease deja de ser confiable. */
  private record Acquired(LockHandle handle, long validUntil) {}

  private Acquired acquire(String resource, Duration lease, Duration waitTimeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + waitTimeout.toNanos();
    while (true) {
      long sent = System.nanoTime();
      Optional<LockHandle> h = lock.tryAcquire(resource, lease);
      if (h.isPresent()) {
        long validUntil = validUntil(sent, lease);
        if (System.nanoTime() - validUntil < 0) {
          return new Acquired(h.get(), validUntil);
        }
        LOG.warn(
            "Lock on {} acquired after its lease ran out ({} ms to answer): released, not used",
            resource,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent));
        releaseQuietly(h.get());
      }
      if (System.nanoTime() - deadline > 0) {
        throw new BusinessException(
            LockErrors.BUSY, "lock not acquired", Map.of("resource", resource));
      }
      Thread.sleep(pollInterval.toMillis());
    }
  }

  /**
   * Hasta cuándo se confía en un lease solicitado en {@code sent}: el lease menos el margen de
   * deriva.
   */
  static long validUntil(long sent, Duration lease) {
    return LeaseTiming.validUntil(sent, lease);
  }

  private static void releaseQuietly(LockHandle handle) {
    try {
      handle.release();
    } catch (RuntimeException e) {
      // el almacén lo expira de todos modos; liberar solo acorta la espera del siguiente
      // propietario
      LOG.debug("Lock on {} not released: {}", handle.resource(), e.getClass().getSimpleName());
    }
  }

  /**
   * Renueva cada tercio del lease. Una renovación que el almacén rechaza pierde el lease de
   * inmediato; una que falla (almacén no disponible) se reintenta, y el lease se pierde cuando se
   * agota su tiempo sin una renovación.
   */
  static void renewLoop(Lease held, Duration lease, AtomicBoolean done) {
    long every = Math.max(10, lease.toMillis() / 3);
    while (!done.get()) {
      try {
        Thread.sleep(every);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (done.get()) {
        return;
      }
      long attempt = System.nanoTime();
      try {
        if (held.handle.renew(lease)) {
          held.heldUntil = validUntil(attempt, lease);
          continue;
        }
        held.lose(null);
      } catch (RuntimeException e) {
        if (attempt - held.heldUntil < 0) {
          LOG.warn(
              "Lock on {} not renewed, retrying: {}",
              held.resource(),
              e.getClass().getSimpleName());
          continue;
        }
        held.lose(e);
      }
      LOG.warn("Lock on {} was lost before the work finished", held.resource());
      return;
    }
  }

  /** Estado de una adquisición, compartido por el trabajo y el renovador. */
  static final class Lease implements LockLease {

    private final LockHandle handle;
    private final Thread worker;
    // nanos monotónicos (System.nanoTime) a partir de los cuales el lease deja de ser confiable
    private volatile long heldUntil;
    private volatile boolean lost;
    private volatile @Nullable Throwable cause;
    // protegido por this: lose() y finish() deciden juntos si el trabajo se interrumpe
    private boolean running = true;
    private boolean interrupted;

    Lease(LockHandle handle, long heldUntil, Thread worker) {
      this.handle = handle;
      this.heldUntil = heldUntil;
      this.worker = worker;
    }

    /** Marca el lease como perdido e interrumpe el trabajo si sigue en ejecución. */
    synchronized void lose(@Nullable Throwable why) {
      if (lost) {
        return;
      }
      cause = why;
      lost = true;
      if (running) {
        interrupted = true;
        worker.interrupt();
      }
    }

    /**
     * La llama el hilo del trabajo cuando el trabajo terminó; limpia la interrupción que causó este
     * lease.
     *
     * @return indica si el lease se perdió
     */
    boolean finish() {
      boolean interruptedByLease;
      synchronized (this) {
        running = false;
        interruptedByLease = interrupted;
      }
      if (interruptedByLease) {
        Thread.interrupted();
      }
      return !held();
    }

    LockLostException lost(@Nullable Throwable during) {
      LockLostException e = new LockLostException(handle.resource(), cause);
      if (during != null) {
        e.addSuppressed(during);
      }
      return e;
    }

    @Override
    public String resource() {
      return handle.resource();
    }

    @Override
    public String token() {
      return handle.token();
    }

    @Override
    public boolean held() {
      return !lost && System.nanoTime() - heldUntil < 0;
    }

    @Override
    public void ensureHeld() {
      if (!held()) {
        throw lost(null);
      }
    }
  }
}
