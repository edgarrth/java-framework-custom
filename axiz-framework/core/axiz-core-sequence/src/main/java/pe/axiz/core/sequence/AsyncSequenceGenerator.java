package pe.axiz.core.sequence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.concurrent.Stages;

/**
 * Asignación de secuencias asíncrona nativa y tolerante a huecos. Los llamadores concurrentes
 * comparten la reserva de un bloque sin esperar en un hilo. Cancelar al último llamador pendiente
 * cancela la reserva nativa; cancelar a un llamador no invalida la asignación de otro.
 */
public final class AsyncSequenceGenerator {
  private final String name;
  private final SequenceFormat format;
  private final AsyncSequenceStore store;
  private final int blockSize;
  private final Clock clock;
  private final AtomicReference<@Nullable Reservation> current = new AtomicReference<>();

  public AsyncSequenceGenerator(
      String name, SequenceFormat format, AsyncSequenceStore store, int blockSize, Clock clock) {
    SequenceAllocation.validate(name, blockSize);
    this.name = name;
    this.format = Objects.requireNonNull(format);
    this.store = Objects.requireNonNull(store);
    this.blockSize = blockSize;
    this.clock = Objects.requireNonNull(clock);
  }

  public CompletionStage<Long> nextValue() {
    return allocate(period());
  }

  public CompletionStage<String> next() {
    String period = period();
    return Stages.map(allocate(period), value -> format.render(period, value));
  }

  private String period() {
    return format.reset().period(LocalDate.now(clock.withZone(format.zone())));
  }

  private CompletionStage<Long> allocate(String period) {
    for (; ; ) {
      Reservation reservation = current.get();
      if (reservation == null || !reservation.period.equals(period)) {
        Reservation fresh = new Reservation(period);
        if (!current.compareAndSet(reservation, fresh)) continue;
        // El propietario se cuenta antes de la publicación, de modo que otro llamador no puede
        // cancelar su trabajo.
        CompletableFuture<Block> owner = fresh.listener();
        fresh.start();
        return Stages.compose(owner, block -> take(fresh, block));
      }
      Block ready = reservation.ready;
      if (ready != null) {
        int offset = ready.cursor.getAndUpdate(value -> Math.min(value + 1, blockSize));
        if (offset < blockSize) return CompletableFuture.completedFuture(ready.first + offset);
        current.compareAndSet(reservation, null);
        continue;
      }
      if (!reservation.retain()) {
        current.compareAndSet(reservation, null);
        continue;
      }
      Reservation pending = reservation;
      return Stages.compose(pending.listener(), block -> take(pending, block));
    }
  }

  private CompletionStage<Long> take(Reservation reservation, Block block) {
    int offset = block.cursor.getAndUpdate(value -> Math.min(value + 1, blockSize));
    if (offset < blockSize) return CompletableFuture.completedFuture(block.first + offset);
    current.compareAndSet(reservation, null);
    return allocate(reservation.period);
  }

  private static final class Block {
    private final long first;
    private final AtomicInteger cursor = new AtomicInteger();

    private Block(long first) {
      this.first = first;
    }
  }

  private final class Reservation {
    private final String period;
    private final AtomicInteger callers = new AtomicInteger(1);
    private final CompletableFuture<Block> result = new CompletableFuture<>();
    private volatile @Nullable Block ready;
    private volatile @Nullable CompletableFuture<Long> nativeCall;

    private Reservation(String period) {
      this.period = period;
    }

    private boolean retain() {
      for (; ; ) {
        int count = callers.get();
        if (count == 0) return false;
        if (callers.compareAndSet(count, Math.incrementExact(count))) return true;
      }
    }

    private CompletableFuture<Block> listener() {
      CompletableFuture<Block> listener = new CompletableFuture<>();
      Stages.observe(
          listener,
          (ignored, error) -> {
            if (callers.decrementAndGet() == 0 && !result.isDone()) {
              current.compareAndSet(this, null);
              result.cancel(true);
              CompletableFuture<Long> active = nativeCall;
              if (active != null) active.cancel(true);
            }
          },
          failure -> {
            listener.completeExceptionally(failure);
            failed(failure);
          });
      Stages.observe(
          result,
          (value, error) -> {
            if (error == null) listener.complete(value);
            else listener.completeExceptionally(Stages.unwrap(error));
          },
          failure -> {
            listener.completeExceptionally(failure);
            failed(failure);
          });
      return listener;
    }

    private void failed(Throwable failure) {
      current.compareAndSet(this, null);
      result.completeExceptionally(failure);
      CompletableFuture<Long> active = nativeCall;
      if (active != null) active.cancel(true);
    }

    private void start() {
      if (result.isDone()) return;
      CompletableFuture<Long> active =
          Stages.call(() -> store.increment(name, period, blockSize)).toCompletableFuture();
      nativeCall = active;
      if (result.isCancelled()) active.cancel(true);
      Stages.observe(
          active,
          (last, error) -> {
            if (error != null) {
              current.compareAndSet(this, null);
              result.completeExceptionally(Stages.unwrap(error));
            } else if (last == null || last < blockSize) {
              current.compareAndSet(this, null);
              result.completeExceptionally(
                  new IllegalStateException("Invalid sequence reservation endpoint"));
            } else if (!result.isDone()) {
              Block block = new Block(SequenceAllocation.first(last, blockSize));
              ready = block;
              result.complete(block);
            }
          },
          this::failed);
    }
  }
}
