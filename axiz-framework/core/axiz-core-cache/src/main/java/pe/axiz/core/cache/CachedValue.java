package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Valor en caché leído junto con lo que el nivel sabe de su expiración: el tiempo que aún le queda
 * de vida, que nunca expira o que el nivel no puede saberlo. Los tres casos son distintos porque
 * una copia local solo puede conservarse mientras se sepa que el original es válido.
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

  /** La entrada expira tras {@code remaining} (medido por el nivel al leerla). */
  public static CachedValue expiringIn(byte[] value, Duration remaining) {
    return new CachedValue(value, Expiry.IN, Objects.requireNonNull(remaining));
  }

  /** La entrada no tiene expiración en el nivel: vive hasta que se desaloja. */
  public static CachedValue withoutExpiry(byte[] value) {
    return new CachedValue(value, Expiry.NEVER, null);
  }

  /** El nivel no puede saber cuándo expira la entrada. */
  public static CachedValue unknownExpiry(byte[] value) {
    return new CachedValue(value, Expiry.UNKNOWN, null);
  }

  /** Copia del valor. */
  public byte[] value() {
    return value.clone();
  }

  /** Tiempo restante antes de que la entrada expire, cuando expira y el nivel sabe cuándo. */
  public Optional<Duration> remaining() {
    return Optional.ofNullable(remaining);
  }

  /** Indica si la entrada vive hasta que se desaloja. */
  public boolean neverExpires() {
    return expiry == Expiry.NEVER;
  }

  /** Indica si el nivel conoce la expiración (un tiempo restante, o ninguna expiración). */
  public boolean expiryKnown() {
    return expiry != Expiry.UNKNOWN;
  }
}
