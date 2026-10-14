package pe.axiz.core.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory store for tests and single-instance local runs. */
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
          if (v == null || v.expiresAt().isBefore(now)) {
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
    return r == null || r.expiresAt().isBefore(now) ? Optional.empty() : Optional.of(r);
  }

  @Override
  public boolean complete(String key, String owner, StoredResponse response, Instant expiresAt) {
    AtomicBoolean done = new AtomicBoolean();
    records.computeIfPresent(
        key,
        (k, v) -> {
          if (!v.heldBy(owner)) {
            return v;
          }
          done.set(true);
          return new IdempotencyRecord(
              k, v.fingerprint(), IdempotencyRecord.Status.COMPLETED, owner, response, expiresAt);
        });
    return done.get();
  }

  @Override
  public boolean release(String key, String owner) {
    AtomicBoolean released = new AtomicBoolean();
    records.computeIfPresent(
        key,
        (k, v) -> {
          if (!v.heldBy(owner)) {
            return v;
          }
          released.set(true);
          return null;
        });
    return released.get();
  }
}
