package pe.axiz.core.messaging;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Port used by the dispatcher to process each message once per consumer (implemented by the inbox).
 * A claim tells three different situations apart, because only one of them allows acknowledging the
 * message without running the handler:
 *
 * <ul>
 *   <li>{@link Claim.Status#ACQUIRED}: this delivery owns the processing until its lease ends; it
 *       must finish with {@link #completed} or {@link #failed} passing the claim's token.
 *   <li>{@link Claim.Status#ALREADY_PROCESSED}: acknowledge, the effects already happened.
 *   <li>{@link Claim.Status#BUSY}: another delivery holds a valid lease and may still fail (or may
 *       have died): the message must be redelivered later, never acknowledged.
 * </ul>
 */
public interface Deduplicator {

  /**
   * Result of claiming a message.
   *
   * @param status what the dispatcher must do
   * @param token owner token of an acquired claim (empty otherwise); later updates of the
   *     registration only succeed with it, so a delivery whose lease was taken over cannot change
   *     the registration of the new owner
   * @param retryAfter for {@link Status#BUSY}, how long until the current lease ends
   */
  record Claim(Status status, String token, Duration retryAfter) {

    /** Claim outcome. */
    public enum Status {
      ACQUIRED,
      ALREADY_PROCESSED,
      BUSY
    }

    public Claim {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(token, "token");
      Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public static Claim acquired(String token) {
      if (token.isBlank()) {
        throw new IllegalArgumentException("token required");
      }
      return new Claim(Status.ACQUIRED, token, Duration.ZERO);
    }

    public static Claim alreadyProcessed() {
      return new Claim(Status.ALREADY_PROCESSED, "", Duration.ZERO);
    }

    public static Claim busy(@Nullable Duration retryAfter) {
      return new Claim(
          Status.BUSY,
          "",
          retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter);
    }

    /** A random owner token. */
    public static String newToken() {
      return UUID.randomUUID().toString();
    }
  }

  /** Claims the message for this delivery. */
  Claim claim(String consumer, String messageKey);

  /**
   * Marks the message processed.
   *
   * @return false when the claim was no longer owned by this token (its lease was taken over)
   */
  boolean completed(String consumer, String messageKey, String token);

  /**
   * Releases the claim after a failure so a redelivery can process it.
   *
   * @return false when the claim was no longer owned by this token
   */
  boolean failed(String consumer, String messageKey, String token);

  static Deduplicator none() {
    return new Deduplicator() {
      @Override
      public Claim claim(String consumer, String messageKey) {
        return Claim.acquired(Claim.newToken());
      }

      @Override
      public boolean completed(String consumer, String messageKey, String token) {
        return true;
      }

      @Override
      public boolean failed(String consumer, String messageKey, String token) {
        return true;
      }
    };
  }
}
