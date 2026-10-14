package pe.axiz.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

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
  private final ReentrantLock lock = new ReentrantLock();
  private long lastMillis = -1L;
  private int counter;

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
    long millis;
    int seq;
    lock.lock();
    try {
      millis = clock.millis();
      if (millis <= lastMillis) {
        millis = lastMillis;
        counter++;
        if (counter > COUNTER_MAX) {
          // counter exhausted: borrow the next millisecond to stay monotonic
          millis = ++lastMillis;
          counter = RANDOM.nextInt(0x400);
        }
      } else {
        lastMillis = millis;
        counter = RANDOM.nextInt(0x400);
      }
      seq = counter;
    } finally {
      lock.unlock();
    }
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
