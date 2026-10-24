package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A cached value read together with what the tier knows about its expiration: the time it still has
 * to live, that it never expires, or that the tier cannot tell. The three cases are distinct
 * because a local copy may only be kept for as long as the original is known to be valid.
 */
public final class CachedValue {

  private enum Expiry {
    IN,
    NEVER,
    UNKNOWN
  }

  private final byte[] value;
  private final Expiry expiry;
  private final @Nullable Duration remaining;

  private CachedValue(byte[] value, Expiry expiry, @Nullable Duration remaining) {
    this.value = value.clone();
    this.expiry = expiry;
    this.remaining = remaining;
  }

  /** The entry expires after {@code remaining} (measured by the tier when it was read). */
  public static CachedValue expiringIn(byte[] value, Duration remaining) {
    return new CachedValue(value, Expiry.IN, Objects.requireNonNull(remaining));
  }

  /** The entry has no expiration in the tier: it lives until it is evicted. */
  public static CachedValue withoutExpiry(byte[] value) {
    return new CachedValue(value, Expiry.NEVER, null);
  }

  /** The tier cannot tell when the entry expires. */
  public static CachedValue unknownExpiry(byte[] value) {
    return new CachedValue(value, Expiry.UNKNOWN, null);
  }

  /** A copy of the value. */
  public byte[] value() {
    return value.clone();
  }

  /** Time left before the entry expires, when it expires and the tier knows when. */
  public Optional<Duration> remaining() {
    return Optional.ofNullable(remaining);
  }

  /** Whether the entry lives until it is evicted. */
  public boolean neverExpires() {
    return expiry == Expiry.NEVER;
  }

  /** Whether the tier knows the expiration (a remaining time, or no expiration at all). */
  public boolean expiryKnown() {
    return expiry != Expiry.UNKNOWN;
  }
}
