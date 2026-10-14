package pe.axiz.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Universally Unique Lexicographically Sortable Identifier: 48-bit timestamp plus 80 random bits,
 * encoded in 26 Crockford base32 characters. Monotonic within the same millisecond.
 */
public final class Ulid implements IdGenerator {

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final Pattern FORMAT = Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Ulid DEFAULT = new Ulid(Clock.systemUTC());

  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private long lastMillis = -1L;
  private long lastRandomHigh; // 16 bits
  private long lastRandomLow; // 64 bits

  private Ulid(Clock clock) {
    this.clock = clock;
  }

  /** Independent generator over the given clock (monotonic per generator). */
  public static Ulid generator(Clock clock) {
    return new Ulid(clock);
  }

  public static String generate() {
    return DEFAULT.newId();
  }

  @Override
  public String newId() {
    long millis = clock.millis();
    long high;
    long low;
    long time;
    lock.lock();
    try {
      if (millis <= lastMillis) {
        time = lastMillis;
        lastRandomLow++;
        if (lastRandomLow == 0) {
          lastRandomHigh = (lastRandomHigh + 1) & 0xFFFF;
          if (lastRandomHigh == 0) {
            throw new IllegalStateException("ULID random component overflow");
          }
        }
      } else {
        time = millis;
        lastMillis = millis;
        lastRandomHigh = RANDOM.nextInt(1 << 16);
        lastRandomLow = RANDOM.nextLong();
      }
      high = lastRandomHigh;
      low = lastRandomLow;
    } finally {
      lock.unlock();
    }
    return encode(time, high, low);
  }

  private static String encode(long time, long randHigh16, long randLow64) {
    char[] out = new char[26];
    // 48-bit time -> 10 chars
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (time & 0x1F)];
      time >>>= 5;
    }
    // 80-bit randomness -> 16 chars; process as two parts: 16 high bits + 64 low bits
    long hi = randHigh16 & 0xFFFF;
    long lo = randLow64;
    for (int i = 25; i >= 10; i--) {
      out[i] = ALPHABET[(int) (lo & 0x1F)];
      lo = (lo >>> 5) | ((hi & 0x1F) << 59);
      hi >>>= 5;
    }
    return new String(out);
  }

  public static boolean isValid(String value) {
    return value != null && FORMAT.matcher(value.toUpperCase(Locale.ROOT)).matches();
  }

  /** Timestamp embedded in a ULID. */
  public static Instant timestamp(String ulid) {
    if (!isValid(ulid)) {
      throw new IllegalArgumentException("Invalid ULID");
    }
    String upper = ulid.toUpperCase(Locale.ROOT);
    long time = 0;
    for (int i = 0; i < 10; i++) {
      time = (time << 5) | indexOf(upper.charAt(i));
    }
    return Instant.ofEpochMilli(time);
  }

  private static int indexOf(char c) {
    for (int i = 0; i < ALPHABET.length; i++) {
      if (ALPHABET[i] == c) {
        return i;
      }
    }
    throw new IllegalArgumentException("Invalid ULID character " + c);
  }
}
