package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A cached value read together with the time it still has to live, when the tier knows it. */
public final class CachedValue {

  private final byte[] value;
  private final @Nullable Duration remaining;

  public CachedValue(byte[] value, @Nullable Duration remaining) {
    this.value = value.clone();
    this.remaining = remaining;
  }

  /** A copy of the value. */
  public byte[] value() {
    return value.clone();
  }

  /** Time left before the entry expires; empty when the tier cannot tell or it never expires. */
  public Optional<Duration> remaining() {
    return Optional.ofNullable(remaining);
  }
}
