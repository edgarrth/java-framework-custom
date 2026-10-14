package pe.axiz.core.contracts;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.test.MutableClock;

/**
 * Properties of every {@link IdempotencyStore}: exclusive start while the lock lives, completion
 * and release fenced by the owner token, takeover after expiry without interference from the
 * previous owner, and retention.
 */
public final class IdempotencyStoreContract {

  /** Lock duration used by the checks. */
  public static final Duration LOCK = Duration.ofSeconds(1);

  /** Retention used by the checks. */
  public static final Duration RETENTION = Duration.ofSeconds(3);

  /** Backend under test. */
  public interface Backend {

    /** A store reading time from {@code clock}; keys of different checks never collide. */
    IdempotencyStore create(MutableClock clock);

    /**
     * Makes the record of {@code key} expire. The default advances the clock past {@link
     * #RETENTION}, enough for stores that compare against their clock; stores whose server expires
     * keys (Redis TTL) wait or delete the key as well.
     */
    default void expire(IdempotencyStore store, String key, MutableClock clock) throws Exception {
      clock.advance(RETENTION.plusSeconds(1));
    }

    /** Start time of the clock; real backends need the current time. */
    default Instant start() {
      return Instant.now();
    }
  }

  private IdempotencyStoreContract() {}

  public static List<ContractCheck> checks(Backend backend) {
    return List.of(
        new ContractCheck("start is exclusive while the lock lives", () -> exclusiveStart(backend)),
        new ContractCheck(
            "only the owner completes, and completed records are kept", () -> complete(backend)),
        new ContractCheck("only the owner releases", () -> release(backend)),
        new ContractCheck(
            "a late owner cannot overwrite or release the key taken by the next owner",
            () -> lateOwner(backend)),
        new ContractCheck("expired records are not found", () -> expiry(backend)));
  }

  private static String key() {
    return "contract:" + UUID.randomUUID();
  }

  private static IdempotencyRecord start(String key, String owner, MutableClock clock) {
    return new IdempotencyRecord(
        key, "fp", IdempotencyRecord.Status.IN_PROGRESS, owner, null, clock.instant().plus(LOCK));
  }

  private static StoredResponse response(String body) {
    return new StoredResponse(201, Map.of("Location", "/x"), body.getBytes(StandardCharsets.UTF_8));
  }

  private static Instant retention(MutableClock clock) {
    return clock.instant().plus(RETENTION);
  }

  private static void exclusiveStart(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), java.time.ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    Expect.isTrue(store.tryStart(start(key, "a", clock)), "first start");
    Expect.isFalse(store.tryStart(start(key, "b", clock)), "second start while locked");
    IdempotencyRecord r = store.find(key, clock.instant()).orElseThrow();
    Expect.equal(IdempotencyRecord.Status.IN_PROGRESS, r.status(), "status");
    Expect.equal("a", r.owner(), "owner");
    Expect.isTrue(store.tryStart(start(key(), "b", clock)), "other keys are independent");
  }

  private static void complete(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), java.time.ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(start(key, "a", clock));
    Expect.isFalse(
        store.complete(key, "b", response("B"), retention(clock)), "a non-owner completes");
    Expect.isTrue(store.complete(key, "a", response("A"), retention(clock)), "owner completes");
    IdempotencyRecord r = store.find(key, clock.instant()).orElseThrow();
    Expect.equal(IdempotencyRecord.Status.COMPLETED, r.status(), "status");
    Expect.equal("fp", r.fingerprint(), "fingerprint kept");
    Expect.isTrue(
        Arrays.equals(
            "A".getBytes(StandardCharsets.UTF_8),
            java.util.Objects.requireNonNull(r.response()).body()),
        "body stored");
    Expect.isFalse(store.complete(key, "a", response("A2"), retention(clock)), "completing twice");
    Expect.isFalse(store.release(key, "a"), "releasing a completed record");
    Expect.isTrue(store.find(key, clock.instant()).isPresent(), "completed record kept");
    Expect.isFalse(
        store.complete(key(), "a", response("x"), retention(clock)), "completing a missing key");
  }

  private static void release(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), java.time.ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(start(key, "a", clock));
    Expect.isFalse(store.release(key, "b"), "a non-owner releases");
    Expect.isTrue(store.release(key, "a"), "owner releases");
    Expect.isTrue(store.find(key, clock.instant()).isEmpty(), "released record removed");
    Expect.isTrue(store.tryStart(start(key, "b", clock)), "released key can start again");
  }

  private static void lateOwner(Backend backend) throws Exception {
    MutableClock clock = new MutableClock(backend.start(), java.time.ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    Expect.isTrue(store.tryStart(start(key, "owner-a", clock)), "A starts");
    backend.expire(store, key, clock);
    Expect.isTrue(store.tryStart(start(key, "owner-b", clock)), "B takes the expired key");
    Expect.isFalse(
        store.complete(key, "owner-a", response("A"), retention(clock)), "late A completes");
    Expect.isFalse(store.release(key, "owner-a"), "late A releases");
    Optional<IdempotencyRecord> current = store.find(key, clock.instant());
    Expect.equal("owner-b", current.orElseThrow().owner(), "B still holds the key");
    Expect.equal(
        IdempotencyRecord.Status.IN_PROGRESS, current.orElseThrow().status(), "B in progress");
    Expect.isTrue(store.complete(key, "owner-b", response("B"), retention(clock)), "B completes");
    Expect.isTrue(
        Arrays.equals(
            "B".getBytes(StandardCharsets.UTF_8),
            java.util.Objects.requireNonNull(
                    store.find(key, clock.instant()).orElseThrow().response())
                .body()),
        "B's response is the stored one");
  }

  private static void expiry(Backend backend) throws Exception {
    MutableClock clock = new MutableClock(backend.start(), java.time.ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(start(key, "a", clock));
    store.complete(key, "a", response("A"), retention(clock));
    backend.expire(store, key, clock);
    Expect.isTrue(store.find(key, clock.instant()).isEmpty(), "expired record found");
    Expect.isTrue(store.tryStart(start(key, "b", clock)), "expired key can start again");
  }
}
