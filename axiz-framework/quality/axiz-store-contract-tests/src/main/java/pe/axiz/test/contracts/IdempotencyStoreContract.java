package pe.axiz.test.contracts;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.Resolution;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.test.support.MutableClock;

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
        new ContractCheck("expired records are not found", () -> expiry(backend)),
        new ContractCheck(
            "lease expiry preserves the uncertain operation", () -> retainedLease(backend)),
        new ContractCheck(
            "renewal preserves ownership and extends retention", () -> renewal(backend)),
        new ContractCheck(
            "unknown resolution is atomic and fenced across reuse", () -> resolution(backend)),
        new ContractCheck(
            "concurrent authoritative resolutions have exactly one winner",
            () -> concurrentResolution(backend)));
  }

  private static String key() {
    return "contract:" + UUID.randomUUID();
  }

  private static IdempotencyRecord start(String key, String owner, MutableClock clock) {
    return IdempotencyRecord.builder(
            key, "fp", clock.instant().plus(LOCK), clock.instant().plus(LOCK))
        .status(IdempotencyRecord.Status.IN_PROGRESS)
        .owner(owner)
        .response(null)
        .resolutionVersion(0)
        .failure(null)
        .build();
  }

  private static StoredResponse response(String body) {
    return new StoredResponse(201, Map.of("Location", "/x"), body.getBytes(StandardCharsets.UTF_8));
  }

  private static Instant retention(MutableClock clock) {
    return clock.instant().plus(RETENTION);
  }

  private static void exclusiveStart(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
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
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
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
            "A".getBytes(StandardCharsets.UTF_8), Objects.requireNonNull(r.response()).body()),
        "body stored");
    Expect.isFalse(store.complete(key, "a", response("A2"), retention(clock)), "completing twice");
    Expect.isFalse(store.release(key, "a"), "releasing a completed record");
    Expect.isTrue(store.find(key, clock.instant()).isPresent(), "completed record kept");
    Expect.isFalse(
        store.complete(key(), "a", response("x"), retention(clock)), "completing a missing key");
  }

  private static void release(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(start(key, "a", clock));
    Expect.isFalse(store.release(key, "b"), "a non-owner releases");
    Expect.isTrue(store.release(key, "a"), "owner releases");
    Expect.isTrue(store.find(key, clock.instant()).isEmpty(), "released record removed");
    Expect.isTrue(store.tryStart(start(key, "b", clock)), "released key can start again");
  }

  private static void lateOwner(Backend backend) throws Exception {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
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
            Objects.requireNonNull(store.find(key, clock.instant()).orElseThrow().response())
                .body()),
        "B's response is the stored one");
  }

  private static void expiry(Backend backend) throws Exception {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(start(key, "a", clock));
    store.complete(key, "a", response("A"), retention(clock));
    backend.expire(store, key, clock);
    Expect.isTrue(store.find(key, clock.instant()).isEmpty(), "expired record found");
    Expect.isTrue(store.tryStart(start(key, "b", clock)), "expired key can start again");
  }

  private static IdempotencyRecord retained(String key, String owner, MutableClock clock) {
    return IdempotencyRecord.builder(
            key, "fp", clock.instant().plus(LOCK), clock.instant().plus(RETENTION))
        .status(IdempotencyRecord.Status.IN_PROGRESS)
        .owner(owner)
        .response(null)
        .resolutionVersion(0)
        .failure(null)
        .build();
  }

  private static void retainedLease(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    Expect.isTrue(store.tryStart(retained(key, "a", clock)), "first execution");
    clock.advance(LOCK.plusMillis(1));
    Expect.isFalse(
        store.tryStart(retained(key, "b", clock)), "expired lease must not free the operation");
    Expect.equal(
        "a", store.find(key, clock.instant()).orElseThrow().owner(), "original identity preserved");
    Expect.isTrue(store.markUnknown(key, "a", "lease-lost", clock.instant()), "mark uncertain");
    Expect.isFalse(store.releaseWithoutEffect(key, "a"), "cannot release an uncertain operation");
    Expect.isFalse(
        store.complete(key, "a", response("late"), retention(clock)),
        "late completion must use reconciliation");
  }

  private static void renewal(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(retained(key, "a", clock));
    Instant lease = clock.instant().plus(LOCK).plusSeconds(1);
    Instant until = lease.plus(RETENTION);
    Expect.isFalse(store.renew(key, "b", lease, until, clock.instant()), "non-owner renews");
    Expect.isTrue(store.renew(key, "a", lease, until, clock.instant()), "owner renews");
    IdempotencyRecord renewed = store.find(key, clock.instant()).orElseThrow();
    Expect.equal(lease, renewed.leaseUntil(), "new lease");
    Expect.equal(until, renewed.retainUntil(), "new retention");
    Expect.isTrue(
        store.complete(key, "a", response("done"), retention(clock)), "complete after renewal");
    Expect.isFalse(
        store.renew(key, "a", lease.plusSeconds(1), until, clock.instant()),
        "completed records cannot renew");
    Expect.isFalse(
        store.renew(key(), "a", lease, until, clock.instant()), "renew cannot recreate a record");
  }

  private static void resolution(Backend backend) {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(retained(key, "a", clock));
    store.markUnknown(key, "a", "transport-error", clock.instant());
    IdempotencyRecord uncertain = store.find(key, clock.instant()).orElseThrow();
    Expect.isTrue(
        store.resolve(uncertain, Resolution.withoutEffect(), clock.instant(), retention(clock)),
        "authoritative no-effect resolution");
    store.tryStart(retained(key, "b", clock));
    store.markUnknown(key, "b", "transport-error", clock.instant());
    Expect.isFalse(
        store.resolve(
            uncertain, Resolution.completed(response("A")), clock.instant(), retention(clock)),
        "old generation cannot resolve a new execution");
    IdempotencyRecord current = store.find(key, clock.instant()).orElseThrow();
    Expect.isTrue(
        store.resolve(
            current, Resolution.completed(response("B")), clock.instant(), retention(clock)),
        "current resolution completes");
    Expect.isFalse(
        store.resolve(current, Resolution.withoutEffect(), clock.instant(), retention(clock)),
        "only one resolution wins");
  }

  private static void concurrentResolution(Backend backend) throws Exception {
    MutableClock clock = new MutableClock(backend.start(), ZoneOffset.UTC);
    IdempotencyStore store = backend.create(clock);
    String key = key();
    store.tryStart(retained(key, "owner", clock));
    store.markUnknown(key, "owner", "unknown", clock.instant());
    IdempotencyRecord expected = store.find(key, clock.instant()).orElseThrow();
    var start = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          workers.submit(
              () -> {
                start.await();
                return store.resolve(
                    expected,
                    Resolution.completed(response("A")),
                    clock.instant(),
                    retention(clock));
              });
      var second =
          workers.submit(
              () -> {
                start.await();
                return store.resolve(
                    expected,
                    Resolution.completed(response("B")),
                    clock.instant(),
                    retention(clock));
              });
      start.countDown();
      int wins =
          (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
      Expect.equal(1, wins, "one resolution commits");
      var completed = store.find(key, clock.instant()).orElseThrow();
      Expect.equal(
          IdempotencyRecord.Status.COMPLETED, completed.status(), "authoritative result retained");
      Expect.isFalse(
          store.releaseWithoutEffect(key, "owner"), "a competing completion cannot be removed");
    }
  }
}
