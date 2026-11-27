package pe.axiz.core.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/** Almacén en memoria para pruebas y ejecuciones locales de una sola instancia. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

  private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryIdempotencyStore() {
    this(Clock.systemUTC());
  }

  public InMemoryIdempotencyStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public boolean tryStart(IdempotencyRecord record) {
    AtomicBoolean started = new AtomicBoolean();
    Instant now = clock.instant();
    records.compute(
        record.key(),
        (k, v) -> {
          if (v == null || !v.liveAt(now)) {
            started.set(true);
            return record;
          }
          return v;
        });
    return started.get();
  }

  @Override
  public Optional<IdempotencyRecord> find(String key, Instant now) {
    IdempotencyRecord r = records.get(key);
    return r == null || !r.liveAt(now) ? Optional.empty() : Optional.of(r);
  }

  @Override
  public boolean compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
    if (replacement != null && !expected.key().equals(replacement.key()))
      throw new IllegalArgumentException("different record key");
    AtomicBoolean changed = new AtomicBoolean();
    records.computeIfPresent(
        expected.key(),
        (key, current) -> {
          if (!current.liveAt(clock.instant()) || !current.equals(expected)) return current;
          changed.set(true);
          return replacement;
        });
    return changed.get();
  }

  @Override
  public boolean complete(String key, String owner, StoredResponse response, Instant expiresAt) {
    AtomicBoolean done = new AtomicBoolean();
    records.computeIfPresent(
        key,
        (k, v) -> {
          if (!v.liveAt(clock.instant()) || !v.heldBy(owner)) {
            return v;
          }
          done.set(true);
          return v.completed(response, expiresAt);
        });
    return done.get();
  }

  @Override
  public boolean release(String key, String owner) {
    AtomicBoolean released = new AtomicBoolean();
    records.computeIfPresent(
        key,
        (k, v) -> {
          if (!v.liveAt(clock.instant()) || !v.heldBy(owner)) {
            return v;
          }
          released.set(true);
          return null;
        });
    return released.get();
  }
}
