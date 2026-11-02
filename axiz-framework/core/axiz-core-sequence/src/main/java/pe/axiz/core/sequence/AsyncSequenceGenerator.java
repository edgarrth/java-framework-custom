package pe.axiz.core.sequence;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;

/**
 * Native asynchronous, gap-tolerant sequence allocation. Concurrent callers share a block
 * reservation without waiting on a thread. Cancelling the last pending caller cancels the native
 * reservation; cancelling one caller does not invalidate another caller's allocation.
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
        // The owner is counted before publication, so another caller cannot cancel its work.
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
      listener.whenComplete(
          (ignored, error) -> {
            if (callers.decrementAndGet() == 0 && !result.isDone()) {
              current.compareAndSet(this, null);
              result.cancel(true);
              CompletableFuture<Long> active = nativeCall;
              if (active != null) active.cancel(true);
            }
          });
      result.whenComplete(
          (value, error) -> {
            if (error == null) listener.complete(value);
            else listener.completeExceptionally(Stages.unwrap(error));
          });
      return listener;
    }

    private void start() {
      CompletableFuture<Long> active =
          Stages.call(() -> store.increment(name, period, blockSize)).toCompletableFuture();
      nativeCall = active;
      if (result.isCancelled()) active.cancel(true);
      active.whenComplete(
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
          });
    }
  }
}
