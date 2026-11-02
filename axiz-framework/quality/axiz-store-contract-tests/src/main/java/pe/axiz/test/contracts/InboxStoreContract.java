package pe.axiz.test.contracts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import pe.axiz.core.messaging.MessageKeys;

/**
 * Properties of every {@link InboxStore}: one delivery processes a message at a time, completion
 * and release are fenced by the owner token, an expired lease is taken over without interference
 * from the previous owner, and processed messages are recognized.
 */
public final class InboxStoreContract {

  static final Duration LEASE = Duration.ofSeconds(30);
  static final Duration RETENTION = Duration.ofDays(7);

  /** Backend under test. */
  public interface Backend {

    /** An empty store (or one whose existing rows do not collide with random message ids). */
    InboxStore create();

    /** {@code false} for stores whose retention is enforced by the server (DynamoDB TTL). */
    default boolean purgesOnDemand() {
      return true;
    }

    /**
     * Instant the checks start at. Stores compare against the instants they receive, so a fixed
     * value works; DynamoDB TTL needs future expiry times, hence the current time by default.
     */
    default Instant start() {
      return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
  }

  private InboxStoreContract() {}

  public static List<ContractCheck> checks(Backend backend) {
    List<ContractCheck> checks = new ArrayList<>();
    checks.add(new ContractCheck("one delivery at a time", () -> exclusive(backend)));
    checks.add(new ContractCheck("only the owner completes", () -> complete(backend)));
    checks.add(
        new ContractCheck(
            "the same id from two sources is two messages", () -> sourceAndId(backend)));
    checks.add(new ContractCheck("only the owner releases", () -> release(backend)));
    checks.add(
        new ContractCheck(
            "an expired lease is taken over and the late owner cannot touch it",
            () -> takeover(backend)));
    if (backend.purgesOnDemand()) {
      checks.add(
          new ContractCheck("processed entries are purged after retention", () -> purge(backend)));
    }
    return checks;
  }

  private static String id() {
    return "m-" + UUID.randomUUID();
  }

  private static Acquisition acquire(InboxStore s, String id, String owner, Instant now) {
    return s.tryAcquire("contract", id, owner, now, now.plus(LEASE), now.plus(RETENTION));
  }

  private static void exclusive(Backend backend) {
    InboxStore store = backend.create();
    Instant now = backend.start();
    String id = id();
    Expect.equal(Acquisition.Status.ACQUIRED, acquire(store, id, "a", now).status(), "first");
    Acquisition busy = acquire(store, id, "b", now.plusSeconds(1));
    Expect.equal(Acquisition.Status.BUSY, busy.status(), "second while leased");
    Expect.equal(now.plus(LEASE), busy.leaseUntil(), "lease end reported");
    Expect.equal(
        Acquisition.Status.ACQUIRED, acquire(store, id(), "b", now).status(), "other messages");
  }

  private static void complete(Backend backend) {
    InboxStore store = backend.create();
    Instant now = backend.start();
    String id = id();
    acquire(store, id, "a", now);
    Expect.isFalse(store.markProcessed("contract", id, "b", now), "a non-owner completes");
    Expect.isTrue(store.markProcessed("contract", id, "a", now), "owner completes");
    Expect.equal(
        Acquisition.Status.PROCESSED,
        acquire(store, id, "c", now.plus(LEASE).plusSeconds(1)).status(),
        "processed is recognized, also after the lease");
    Expect.isFalse(store.release("contract", id, "a"), "processed entries are kept");
    Expect.isFalse(store.markProcessed("contract", id, "a", now), "completing twice");
  }

  private static void sourceAndId(Backend backend) {
    InboxStore store = backend.create();
    Instant now = backend.start();
    String id = id();
    String fromA = MessageKeys.of("urn:contract:a", id);
    String fromB = MessageKeys.of("urn:contract:b", id);
    acquire(store, fromA, "a", now);
    Expect.isTrue(store.markProcessed("contract", fromA, "a", now), "A processed");
    Expect.equal(Acquisition.Status.ACQUIRED, acquire(store, fromB, "b", now).status(), "B is new");
    Expect.equal(
        Acquisition.Status.PROCESSED,
        acquire(store, fromA, "c", now.plus(LEASE).plusSeconds(1)).status(),
        "A again is a duplicate");
  }

  private static void release(Backend backend) {
    InboxStore store = backend.create();
    Instant now = backend.start();
    String id = id();
    acquire(store, id, "a", now);
    Expect.isFalse(store.release("contract", id, "b"), "a non-owner releases");
    Expect.isTrue(store.release("contract", id, "a"), "owner releases");
    Expect.equal(
        Acquisition.Status.ACQUIRED,
        acquire(store, id, "b", now.plusSeconds(1)).status(),
        "redelivery after release");
  }

  private static void takeover(Backend backend) {
    InboxStore store = backend.create();
    Instant now = backend.start();
    String id = id();
    acquire(store, id, "owner-a", now);
    Instant later = now.plus(LEASE).plusSeconds(1);
    Expect.equal(
        Acquisition.Status.ACQUIRED,
        acquire(store, id, "owner-b", later).status(),
        "B takes the expired lease");
    Expect.isFalse(store.markProcessed("contract", id, "owner-a", later), "late A completes");
    Expect.isFalse(store.release("contract", id, "owner-a"), "late A releases");
    Expect.equal(
        Acquisition.Status.BUSY,
        acquire(store, id, "owner-c", later.plusSeconds(1)).status(),
        "B still holds the lease");
    Expect.isTrue(store.markProcessed("contract", id, "owner-b", later), "B completes");
  }

  private static void purge(Backend backend) {
    InboxStore store = backend.create();
    Instant now = backend.start();
    String id = id();
    acquire(store, id, "a", now);
    store.markProcessed("contract", id, "a", now);
    Expect.isTrue(store.purgeExpired(now.plus(RETENTION).plusSeconds(1)) >= 1, "purged");
    Expect.equal(
        Acquisition.Status.ACQUIRED,
        acquire(store, id, "b", now.plus(RETENTION).plusSeconds(2)).status(),
        "a purged message is new again");
  }
}
