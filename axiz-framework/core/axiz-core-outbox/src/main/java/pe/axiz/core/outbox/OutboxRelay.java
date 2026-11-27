package pe.axiz.core.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.concurrent.LeaseTiming;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.errors.Failures;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.resilience.Backoff;

/**
 * Mueve las entradas del outbox al broker (al menos una vez). Cada ejecución bloquea un lote,
 * publica en orden y marca cada entrada como publicada o programa un reintento con backoff; tras
 * {@code maxAttempts} la entrada se marca como FAILED y se informa. Los relays concurrentes en
 * PostgreSQL omiten las filas bloqueadas.
 *
 * <p>Un plazo monotónico, capturado antes de obtener la conexión del claim, impide iniciar una
 * publicación sin lease restante suficiente para su presupuesto declarado. Es una garantía de
 * admisión: las pausas del proceso y las operaciones del broker que continúan después de un timeout
 * aún pueden exceder el lease. El fencing por propietario y los consumidores idempotentes siguen
 * siendo necesarios para los resultados inciertos y las entregas duplicadas físicas.
 */
public final class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxStore store;
  private final PayloadProtector protector;

  private final EventPublisher publisher;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final int batchSize;
  private final int maxAttempts;
  private final Backoff backoff;
  private final Duration lease;
  private final Duration publishBudget;
  private final LongSupplier nanoTime;
  private final OutboxRelayListener listener;

  /** Compatibilidad de enrutamiento declarada por el publicador efectivo; no consulta al broker. */
  public boolean supportsDestination(String destination) {
    return publisher.supports(destination);
  }

  /**
   * Inicia la construcción explícita; deben suministrarse los presupuestos de lote, reintento y
   * publicación.
   */
  public static Builder builder(
      OutboxStore store, EventPublisher publisher, TransactionRunner transactions, Clock clock) {
    return new Builder(store, publisher, transactions, clock);
  }

  /** Estado mutable de construcción; build valida y crea un valor inmutable independiente. */
  public static final class Builder {
    private final OutboxStore store;
    private final EventPublisher publisher;
    private final TransactionRunner transactions;
    private final Clock clock;
    private int batchSize = 0;
    private int maxAttempts = 0;
    private @Nullable Backoff backoff = null;
    private @Nullable Duration lease = null;
    private @Nullable Duration publishBudget = null;
    private OutboxRelayListener listener = OutboxRelayListener.NOOP;
    private LongSupplier nanoTime = System::nanoTime;
    private PayloadProtector protector = PayloadProtector.NONE;

    private Builder(
        OutboxStore store, EventPublisher publisher, TransactionRunner transactions, Clock clock) {
      this.store = Objects.requireNonNull(store);
      this.publisher = Objects.requireNonNull(publisher);
      this.transactions = Objects.requireNonNull(transactions);
      this.clock = Objects.requireNonNull(clock);
    }

    public Builder batchSize(int value) {
      this.batchSize = value;
      return this;
    }

    public Builder maxAttempts(int value) {
      this.maxAttempts = value;
      return this;
    }

    public Builder backoff(Backoff value) {
      this.backoff = value;
      return this;
    }

    public Builder lease(Duration value) {
      this.lease = value;
      return this;
    }

    public Builder publishBudget(Duration value) {
      this.publishBudget = value;
      return this;
    }

    public Builder listener(OutboxRelayListener value) {
      this.listener = value;
      return this;
    }

    Builder ticker(LongSupplier value) {
      this.nanoTime = value;
      return this;
    }

    public Builder protector(PayloadProtector value) {
      this.protector = value;
      return this;
    }

    public OutboxRelay build() {
      return new OutboxRelay(this);
    }
  }

  private OutboxRelay(Builder builder) {
    OutboxStore store = builder.store;
    EventPublisher publisher = builder.publisher;
    TransactionRunner transactions = builder.transactions;
    Clock clock = builder.clock;
    int batchSize = builder.batchSize;
    int maxAttempts = builder.maxAttempts;
    Backoff backoff = Objects.requireNonNull(builder.backoff, "backoff");
    Duration lease = Objects.requireNonNull(builder.lease, "lease");
    Duration publishBudget = Objects.requireNonNull(builder.publishBudget, "publishBudget");
    OutboxRelayListener listener = builder.listener;
    LongSupplier nanoTime = builder.nanoTime;
    PayloadProtector protector = builder.protector;
    this.protector = Objects.requireNonNull(protector);
    this.store = Objects.requireNonNull(store);
    this.publisher = Objects.requireNonNull(publisher);
    this.transactions = Objects.requireNonNull(transactions);
    this.clock = Objects.requireNonNull(clock);
    if (batchSize < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("batchSize and maxAttempts must be positive");
    }
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.backoff = Objects.requireNonNull(backoff);
    this.lease = Objects.requireNonNull(lease);
    if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive");
    this.publishBudget = Objects.requireNonNull(publishBudget);
    if (!publishBudget.isPositive()
        || lease.minus(LeaseTiming.margin(lease)).compareTo(publishBudget) < 0)
      throw new IllegalArgumentException(
          "lease must cover the positive publication budget and margin");
    this.nanoTime = Objects.requireNonNull(nanoTime);
    this.listener = Objects.requireNonNull(listener);
  }

  /** Resultado de una ejecución del relay. */
  public record RunResult(int published, int retried, int failed) {
    public int total() {
      return published + retried + failed;
    }
  }

  private <T> T transaction(Callable<T> work) {
    try {
      return transactions.inTransaction(work);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new OutboxException("Outbox transaction failed", failure);
    }
  }

  public RunResult relayOnce() {
    int published = 0;
    int retried = 0;
    int failed = 0;
    RunResult result;
    do {
      result = relayClaim(batchSize - published - retried - failed);
      published += result.published();
      retried += result.retried();
      failed += result.failed();
    } while (published + retried + failed < batchSize && result.published() > 0);
    return new RunResult(published, retried, failed);
  }

  private RunResult relayClaim(int limit) {
    long t0 = nanoTime.getAsLong();
    String owner = UUID.randomUUID().toString();
    List<OutboxRecord> batch = transaction(() -> store.claimDue(limit, lease, owner));
    long deadline = LeaseTiming.validUntil(t0, lease);
    listener.onClaim(Duration.ofNanos(nanoTime.getAsLong() - t0), batch.size());
    int published = 0;
    int retried = 0;
    int failed = 0;
    int remaining = batch.size();
    for (OutboxRecord r : batch) {
      if (deadline - nanoTime.getAsLong() < publishBudget.toNanos()) {
        listener.onBudgetExhausted(remaining);
        break;
      }
      remaining--;
      try {
        var event = r.event(protector);
        if (deadline - nanoTime.getAsLong() < publishBudget.toNanos()) {
          listener.onBudgetExhausted(remaining + 1);
          break;
        }
        publisher.publish(r.destination(), event);
        if (transaction(() -> store.markPublished(r.id(), owner, clock.instant()))) published++;
      } catch (RuntimeException e) {
        int attempts = r.attempts() + 1;
        boolean giveUp = attempts >= maxAttempts;
        Duration delay = backoff.delay(attempts);
        if (!transaction(
            () ->
                store.markFailed(
                    r.id(),
                    owner,
                    attempts,
                    clock.instant().plus(delay),
                    e.getClass().getSimpleName(),
                    giveUp))) continue;
        if (giveUp) {
          failed++;
          LOG.error(
              "Outbox entry {} ({}) failed permanently after {} attempts; failure={}",
              r.id(),
              r.eventType(),
              attempts,
              Failures.describe(e));
        } else {
          retried++;
          LOG.warn(
              "Outbox entry {} ({}) failed, retry {} in {}",
              r.id(),
              r.eventType(),
              attempts,
              delay);
        }
      }
    }
    return new RunResult(published, retried, failed);
  }

  /**
   * Se ejecuta hasta que un lote vuelve con menos elementos que el tamaño de lote (drena el
   * atraso).
   */
  public int drain() {
    int total = 0;
    RunResult r;
    do {
      r = relayOnce();
      total += r.published();
    } while (r.published() > 0);
    return total;
  }
}
