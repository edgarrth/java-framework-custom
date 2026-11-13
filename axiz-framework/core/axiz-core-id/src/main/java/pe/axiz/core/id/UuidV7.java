package pe.axiz.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-ordered UUID version 7 (RFC 9562). Within the same millisecond a 12-bit counter in {@code
 * rand_a} keeps values monotonic (RFC 9562 section 6.2, method 1); if the clock moves backwards the
 * last timestamp is reused so ordering is preserved.
 *
 * <p>{@link #generate()} uses a shared generator on the UTC system clock; {@link #generator(Clock)}
 * creates an independent generator, for example for tests.
 */
public final class UuidV7 implements IdGenerator {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int COUNTER_MAX = 0xFFF;
  private static final UuidV7 DEFAULT = new UuidV7(Clock.systemUTC());

  private final Clock clock;
  // Timestamp (48 bits) and monotonic counter (12 bits) are reserved in one CAS operation.
  private final AtomicLong sequence = new AtomicLong(-1L);

  private UuidV7(Clock clock) {
    this.clock = clock;
  }

  public static UuidV7 generator(Clock clock) {
    return new UuidV7(clock);
  }

  public static UUID generate() {
    return DEFAULT.next();
  }

  /** Next UUIDv7 of this generator. */
  public UUID next() {
    long now = clock.millis();
    if (now < 0 || now > 0xFFFF_FFFF_FFFFL)
      throw new IllegalArgumentException("UUIDv7 requires a 48-bit Unix millisecond timestamp");
    long previous;
    long reserved;
    do {
      previous = sequence.get();
      if (previous == 0x0FFF_FFFF_FFFF_FFFFL)
        throw new IllegalStateException("UUIDv7 timestamp range exhausted");
      reserved =
          previous < 0 || now > (previous >>> 12)
              ? (now << 12) | RANDOM.nextInt(0x400)
              : previous + 1; // Counter overflow borrows the next millisecond, without waiting.
    } while (!sequence.compareAndSet(previous, reserved));
    long millis = reserved >>> 12;
    int seq = (int) (reserved & COUNTER_MAX);
    long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
    msb |= 0x7000L; // version 7
    msb |= seq & COUNTER_MAX;
    long lsb = RANDOM.nextLong();
    lsb &= 0x3FFF_FFFF_FFFF_FFFFL;
    lsb |= 0x8000_0000_0000_0000L; // IETF variant
    return new UUID(msb, lsb);
  }

  @Override
  public String newId() {
    return next().toString();
  }

  public static boolean isV7(UUID uuid) {
    return uuid.version() == 7 && uuid.variant() == 2;
  }

  /** Creation instant embedded in a UUIDv7. */
  public static Instant timestamp(UUID uuid) {
    if (!isV7(uuid)) {
      throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
    }
    return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
  }
}
