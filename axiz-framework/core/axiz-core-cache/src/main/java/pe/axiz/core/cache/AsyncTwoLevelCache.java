package pe.axiz.core.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.Stages;

/**
 * Native asynchronous L2 with CPU-only L1. Local copies discount the complete read duration in both
 * clocks.
 */
public final class AsyncTwoLevelCache implements AsyncByteCache {
  private final LocalByteCache l1;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "AsyncByteCache is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final @Nullable AsyncByteCache l2;

  private final Duration maxTtl;
  private final LongSupplier nanoTime;

  public AsyncTwoLevelCache(LocalByteCache l1, @Nullable AsyncByteCache l2, Duration maxTtl) {
    this(l1, l2, maxTtl, System::nanoTime);
  }

  /** Controlled monotonic clock for contract tests; expiration also respects the L1 clock. */
  public AsyncTwoLevelCache(
      LocalByteCache l1, @Nullable AsyncByteCache l2, Duration maxTtl, LongSupplier nanoTime) {
    this.nanoTime = Objects.requireNonNull(nanoTime);
    this.l1 = Objects.requireNonNull(l1);
    this.l2 = l2;
    this.maxTtl = Objects.requireNonNull(maxTtl);
  }

  private Duration capped(Duration ttl) {
    return ttl.compareTo(maxTtl) < 0 ? ttl : maxTtl;
  }

  @Override
  public CompletionStage<Optional<byte[]>> get(String key) {
    return Stages.map(getWithTtl(key), value -> value.map(CachedValue::value));
  }

  @Override
  public CompletionStage<Optional<CachedValue>> getWithTtl(String key) {
    Optional<CachedValue> local = l1.getWithTtl(key);
    AsyncByteCache remote = l2;
    if (local.isPresent() || remote == null) return CompletableFuture.completedFuture(local);
    long start = nanoTime.getAsLong();
    long localStart = l1.ticks();
    return Stages.recover(
        Stages.map(
            Stages.call(() -> remote.getWithTtl(key)),
            entry -> {
              if (entry.isEmpty()) return entry;
              CachedValue value = entry.get();
              Duration took =
                  Duration.ofNanos(
                      Math.max(0, Math.max(nanoTime.getAsLong() - start, l1.ticks() - localStart)));
              Duration ttl =
                  !value.expiryKnown()
                      ? Duration.ZERO
                      : value.neverExpires()
                          ? maxTtl
                          : capped(value.remaining().orElse(Duration.ZERO).minus(took));
              if (ttl.isPositive()) l1.put(key, value.value(), ttl);
              return value.expiryKnown() && !value.neverExpires()
                  ? Optional.of(
                      CachedValue.expiringIn(
                          value.value(), value.remaining().orElse(Duration.ZERO).minus(took)))
                  : entry;
            }),
        error -> {
          log("read", error);
          return Optional.empty();
        });
  }

  @Override
  public CompletionStage<Void> put(String key, byte[] value, Duration ttl) {
    l1.put(key, value, capped(ttl));
    AsyncByteCache remote = l2;
    return remote == null
        ? CompletableFuture.completedFuture(null)
        : Stages.settle(
            Stages.call(() -> remote.put(key, value, ttl)), error -> log("write", error));
  }

  @Override
  public CompletionStage<Void> evict(String key) {
    l1.evict(key);
    AsyncByteCache remote = l2;
    return remote == null
        ? CompletableFuture.completedFuture(null)
        : Stages.settle(Stages.call(() -> remote.evict(key)), error -> log("evict", error));
  }

  @Override
  public CompletionStage<Long> evictByPrefix(String prefix) {
    long local = l1.evictByPrefix(prefix);
    AsyncByteCache remote = l2;
    return remote == null
        ? CompletableFuture.completedFuture(local)
        : Stages.recover(
            Stages.map(Stages.call(() -> remote.evictByPrefix(prefix)), count -> local + count),
            error -> {
              log("evict prefix", error);
              return local;
            });
  }

  private static void log(String operation, Throwable failure) {
    LoggerFactory.getLogger(AsyncTwoLevelCache.class)
        .warn(
            "L2 cache {} failed; continuing without cache: {}",
            operation,
            failure.getClass().getSimpleName());
  }
}
