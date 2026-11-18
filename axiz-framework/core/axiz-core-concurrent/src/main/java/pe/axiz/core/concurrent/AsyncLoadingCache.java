package pe.axiz.core.concurrent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, coalesced native asynchronous loads with TTL, maximum uses and caller-owned
 * cancellation. Loader I/O runs outside the short bookkeeping lock; no executor or blocking wait is
 * introduced. Cancelling the last pending caller cancels the native future. Missing values can opt
 * out of caching.
 *
 * @param <V> immutable cached value
 */
public final class AsyncLoadingCache<V> implements AutoCloseable {
  private final Map<String, Slot> entries = new LinkedHashMap<>(16, 0.75f, true);
  private final Duration maxAge;
  private final long maxUses;
  private final Clock clock;
  private final int capacity;
  private final Predicate<? super V> cacheable;
  private final UnaryOperator<V> snapshot;
  private final Consumer<V> release;
  private final Set<Slot> loading = new HashSet<>();
  private int pendingLoads;
  private boolean closed;

  public AsyncLoadingCache(Duration maxAge, long maxUses, int capacity, Clock clock) {
    this(maxAge, maxUses, capacity, clock, value -> true);
  }

  public AsyncLoadingCache(
      Duration maxAge, long maxUses, int capacity, Clock clock, Predicate<? super V> cacheable) {
    this(maxAge, maxUses, capacity, clock, cacheable, UnaryOperator.identity(), value -> {});
  }

  /**
   * Owned-value variant. Snapshot must return an independent caller-owned value; release erases
   * retired values and snapshots whose caller cancelled. Both callbacks must be short, CPU-only and
   * non-reentrant; release must not throw. They run under the bookkeeping lock to prevent eviction
   * from invalidating a value while a snapshot is being taken.
   */
  public AsyncLoadingCache(
      Duration maxAge,
      long maxUses,
      int capacity,
      Clock clock,
      Predicate<? super V> cacheable,
      UnaryOperator<V> snapshot,
      Consumer<V> release) {
    this.maxAge = Objects.requireNonNull(maxAge);
    if (maxAge.isNegative() || maxUses < 1 || capacity < 1)
      throw new IllegalArgumentException(
          "Cache age must be nonnegative; uses and capacity must be positive");
    this.maxUses = maxUses;
    this.capacity = capacity;
    this.clock = Objects.requireNonNull(clock);
    this.cacheable = Objects.requireNonNull(cacheable);
    this.snapshot = Objects.requireNonNull(snapshot);
    this.release = Objects.requireNonNull(release);
  }

  /** Values must be immutable. Rejected submissions and load failures complete exceptionally. */
  public CompletionStage<V> get(String id, Supplier<CompletionStage<V>> loader) {
    return get(id, loader, hit -> {});
  }

  /** Observes each lookup outside the bookkeeping lock; observer failures cannot change results. */
  public CompletionStage<V> get(
      String id, Supplier<CompletionStage<V>> loader, Consumer<Boolean> lookup) {
    boolean[] hit = {false};
    return Stages.call(
        () -> {
          try {
            return find(Objects.requireNonNull(id), Objects.requireNonNull(loader), hit);
          } finally {
            try {
              lookup.accept(hit[0]);
            } catch (RuntimeException ignored) {
              // Diagnostics must not change cache correctness or cancellation.
            }
          }
        });
  }

  /**
   * Removes the cached value without cancelling existing callers or retaining their later result.
   */
  public void evict(String id) {
    synchronized (entries) {
      Slot removed = entries.remove(id);
      if (removed != null) removed.retire();
    }
  }

  /**
   * Stops new reads, cancels pending loads and releases owned entries; delivered snapshots survive.
   */
  @Override
  public void close() {
    List<Slot> pending;
    synchronized (entries) {
      if (closed) return;
      closed = true;
      entries.values().forEach(Slot::retire);
      entries.clear();
      pending = List.copyOf(loading);
      pending.forEach(Slot::retire);
    }
    pending.forEach(
        slot -> {
          slot.result.cancel(true);
          var active = slot.nativeCall;
          if (active != null) active.cancel(true);
        });
  }

  private CompletionStage<V> find(String id, Supplier<CompletionStage<V>> loader, boolean[] hit) {
    Slot selected;
    boolean start;
    synchronized (entries) {
      if (closed) throw new RejectedExecutionException("Cache is closed");
      Slot existing = entries.get(id);
      Ready<V> ready = existing == null ? null : existing.ready;
      if (existing != null
          && existing.uses < maxUses
          && ready != null
          && clock.instant().isBefore(ready.created().plus(maxAge))) {
        existing.uses++;
        hit[0] = true;
        return CompletableFuture.completedFuture(snapshot.apply(ready.value()));
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
        loading.add(selected);
        Slot replaced = entries.put(id, selected);
        if (replaced != null) replaced.retire();
        if (entries.size() > capacity) {
          Slot removed = entries.remove(entries.keySet().iterator().next());
          if (removed != null) removed.retire();
        }
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
    private @Nullable V owned;
    private boolean retired;
    private long uses = 1;

    private Slot(String id) {
      this.id = id;
    }

    // All ownership transitions run under entries' lock, including the snapshot callback.
    private void retire() {
      retired = true;
      disposeIfUnused();
    }

    private void disposeIfUnused() {
      V value = owned;
      if (retired && callers.get() == 0 && value != null) {
        owned = null;
        ready = null;
        release.accept(value);
      }
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
      Stages.observe(
          listener,
          (ignored, error) -> {
            boolean cancel;
            synchronized (entries) {
              cancel = callers.decrementAndGet() == 0 && !result.isDone();
              if (cancel) {
                entries.remove(id, this);
                retire();
              }
              disposeIfUnused();
            }
            if (cancel) {
              result.cancel(true);
              var active = nativeCall;
              if (active != null) active.cancel(true);
            }
          },
          failure -> {
            listener.completeExceptionally(failure);
            finish(null, failure);
            var active = nativeCall;
            if (active != null) active.cancel(true);
          });
      Stages.observe(
          result,
          (value, error) -> {
            if (error == null) {
              V copy;
              synchronized (entries) {
                if (listener.isDone()) return;
                copy = snapshot.apply(Objects.requireNonNull(value));
              }
              if (!listener.complete(copy)) {
                synchronized (entries) {
                  release.accept(copy);
                }
              }
            } else listener.completeExceptionally(Stages.unwrap(error));
          },
          listener::completeExceptionally);
      return listener;
    }

    private void start(Supplier<CompletionStage<V>> loader) {
      if (result.isDone()) {
        finish(null, new CancellationException());
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
        loading.remove(this);
        owned = value;
        if (error != null || result.isCancelled() || completed == null || maxAge.isZero()) {
          entries.remove(id, this);
          retired = true;
        } else if (!retired) ready = completed;
        disposeIfUnused();
      }
      if (error == null) result.complete(Objects.requireNonNull(value));
      else result.completeExceptionally(Stages.unwrap(error));
    }
  }
}
