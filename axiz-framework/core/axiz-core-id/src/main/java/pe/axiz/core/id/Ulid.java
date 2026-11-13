package pe.axiz.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
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
  private final AtomicReference<State> state = new AtomicReference<>(new State(-1L, 0, 0));

  private record State(long millis, long high, long low) {}

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
    if (millis < 0 || millis > 0xFFFF_FFFF_FFFFL)
      throw new IllegalArgumentException("ULID requires a 48-bit Unix millisecond timestamp");
    State previous;
    State reserved;
    do {
      previous = state.get();
      if (millis <= previous.millis()) {
        long low = previous.low() + 1;
        long high = low == 0 ? (previous.high() + 1) & 0xFFFF : previous.high();
        if (low == 0 && high == 0)
          throw new IllegalStateException("ULID random component overflow");
        reserved = new State(previous.millis(), high, low);
      } else {
        reserved = new State(millis, RANDOM.nextInt(1 << 16), RANDOM.nextLong());
      }
    } while (!state.compareAndSet(previous, reserved));
    return encode(reserved.millis(), reserved.high(), reserved.low());
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
