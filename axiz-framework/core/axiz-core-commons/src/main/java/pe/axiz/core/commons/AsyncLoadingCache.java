package pe.axiz.core.commons;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, coalesced native asynchronous loads with TTL, maximum uses and caller-owned
 * cancellation. Loader I/O runs outside the short bookkeeping lock; no executor or blocking wait is
 * introduced. Cancelling the last pending caller cancels the native future. Missing values can opt
 * out of caching.
 *
 * @param <V> immutable cached value
 */
public final class AsyncLoadingCache<V> {
  private final Map<String, Slot> entries = new LinkedHashMap<>(16, 0.75f, true);
  private final Duration maxAge;
  private final long maxUses;
  private final Clock clock;
  private final int capacity;
  private final Predicate<? super V> cacheable;
  private int pendingLoads;

  public AsyncLoadingCache(Duration maxAge, long maxUses, int capacity, Clock clock) {
    this(maxAge, maxUses, capacity, clock, value -> true);
  }

  public AsyncLoadingCache(
      Duration maxAge, long maxUses, int capacity, Clock clock, Predicate<? super V> cacheable) {
    this.maxAge = Objects.requireNonNull(maxAge);
    if (maxAge.isNegative() || maxUses < 1 || capacity < 1)
      throw new IllegalArgumentException(
          "Cache age must be nonnegative; uses and capacity must be positive");
    this.maxUses = maxUses;
    this.capacity = capacity;
    this.clock = Objects.requireNonNull(clock);
    this.cacheable = Objects.requireNonNull(cacheable);
  }

  /** Values must be immutable. Rejected submissions and load failures complete exceptionally. */
  public CompletionStage<V> get(String id, Supplier<CompletionStage<V>> loader) {
    return Stages.call(() -> find(Objects.requireNonNull(id), Objects.requireNonNull(loader)));
  }

  /**
   * Removes the cached value without cancelling existing callers or retaining their later result.
   */
  public void evict(String id) {
    synchronized (entries) {
      entries.remove(id);
    }
  }

  private CompletionStage<V> find(String id, Supplier<CompletionStage<V>> loader) {
    Slot selected;
    boolean start;
    synchronized (entries) {
      Slot existing = entries.get(id);
      Ready<V> ready = existing == null ? null : existing.ready;
      if (existing != null
          && existing.uses < maxUses
          && ready != null
          && clock.instant().isBefore(ready.created().plus(maxAge))) {
        existing.uses++;
        return CompletableFuture.completedFuture(ready.value());
      }
      if (existing != null && ready == null && existing.uses < maxUses && existing.retain()) {
        existing.uses++;
        selected = existing;
        start = false;
      } else {
        if (pendingLoads >= capacity)
          throw new RejectedExecutionException("Too many pending asynchronous cache loads");
        pendingLoads++;
        selected = new Slot(id);
        entries.put(id, selected);
        if (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
        start = true;
      }
    }
    // Each caller was retained before publication. A cancellation cannot steal another caller.
    CompletableFuture<V> listener = selected.listener();
    if (start) selected.start(loader);
    return listener;
  }

  private record Ready<V>(V value, Instant created) {}

  private final class Slot {
    private final String id;
    private final AtomicInteger callers = new AtomicInteger(1);
    private final AtomicBoolean finished = new AtomicBoolean();
    private final CompletableFuture<V> result = new CompletableFuture<>();
    private volatile @Nullable Ready<V> ready;
    private volatile @Nullable CompletableFuture<V> nativeCall;
    private long uses = 1;

    private Slot(String id) {
      this.id = id;
    }

    private boolean retain() {
      for (; ; ) {
        int count = callers.get();
        if (count == 0) return false;
        if (callers.compareAndSet(count, Math.incrementExact(count))) return true;
      }
    }

    private CompletableFuture<V> listener() {
      CompletableFuture<V> listener = new CompletableFuture<>();
      var _ =
          listener.whenComplete(
              (ignored, error) -> {
                if (callers.decrementAndGet() == 0 && !result.isDone()) {
                  synchronized (entries) {
                    entries.remove(id, this);
                  }
                  result.cancel(true);
                  var active = nativeCall;
                  if (active != null) active.cancel(true);
                }
              });
      Stages.observe(
          result,
          (value, error) -> {
            if (error == null) listener.complete(value);
            else listener.completeExceptionally(Stages.unwrap(error));
          },
          listener::completeExceptionally);
      return listener;
    }

    private void start(Supplier<CompletionStage<V>> loader) {
      if (result.isCancelled()) {
        finish(null, new java.util.concurrent.CancellationException());
        return;
      }
      var active = Stages.call(loader).toCompletableFuture();
      nativeCall = active;
      if (result.isCancelled()) active.cancel(true);
      Stages.observe(
          active,
          this::finish,
          error -> {
            active.cancel(true);
            finish(null, error);
          });
    }

    private void finish(@Nullable V value, @Nullable Throwable failure) {
      if (!finished.compareAndSet(false, true)) return;
      nativeCall = null;
      Throwable error = failure;
      Ready<V> completed = null;
      if (error == null) {
        try {
          V required = Objects.requireNonNull(value, "Cache loader returned no value");
          if (cacheable.test(required)) completed = new Ready<>(required, clock.instant());
        } catch (RuntimeException | Error invalid) {
          error = invalid;
        }
      }
      synchronized (entries) {
        pendingLoads--;
        if (error != null || result.isCancelled() || completed == null) entries.remove(id, this);
        else ready = completed;
      }
      if (error == null) result.complete(Objects.requireNonNull(value));
      else result.completeExceptionally(Stages.unwrap(error));
    }
  }
}
