package pe.axiz.core.inbox;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Registry of consumed messages per consumer (idempotent consumer pattern). Each in-progress
 * registration has an owner token: completing or releasing it only works for the delivery that
 * holds it, so a delivery whose lease expired and was taken over cannot change the registration of
 * the new owner.
 */
public interface InboxStore {

  /**
   * Result of an acquisition attempt.
   *
   * @param status what happened
   * @param leaseUntil end of the current lease when {@link Status#BUSY}
   */
  record Acquisition(Status status, @Nullable Instant leaseUntil) {

    /** Acquisition outcome. */
    public enum Status {
      /** This caller owns the processing. */
      ACQUIRED,
      /** Already processed by an earlier delivery. */
      PROCESSED,
      /** Another delivery holds a valid lease. */
      BUSY
    }

    public Acquisition {
      Objects.requireNonNull(status, "status");
    }

    public static Acquisition acquired() {
      return new Acquisition(Status.ACQUIRED, null);
    }

    public static Acquisition processed() {
      return new Acquisition(Status.PROCESSED, null);
    }

    public static Acquisition busy(@Nullable Instant leaseUntil) {
      return new Acquisition(Status.BUSY, leaseUntil);
    }
  }

  /**
   * Registers the message as in progress for {@code owner} until {@code leaseUntil}, or takes over
   * a registration whose lease ended (its delivery died).
   */
  Acquisition tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt);

  /**
   * Marks the message processed.
   *
   * @return false when {@code owner} no longer holds the registration
   */
  boolean markProcessed(String consumer, String messageId, String owner, Instant at);

  /**
   * Removes the in-progress registration of {@code owner} after a failure so a redelivery can
   * process it.
   *
   * @return false when {@code owner} no longer holds the registration
   */
  boolean release(String consumer, String messageId, String owner);

  /** Deletes entries past their retention; returns how many. */
  int purgeExpired(Instant now);

  /**
   * Checks at startup that the storage has the layout this version needs (for example the {@code
   * owner} column), so an upgrade that skipped a migration fails before consuming messages.
   *
   * @throws InboxException with the upgrade to apply
   */
  default void verifySchema() {}
}
