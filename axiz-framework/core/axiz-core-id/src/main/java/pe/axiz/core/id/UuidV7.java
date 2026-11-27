package pe.axiz.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUID versión 7 ordenado por tiempo (RFC 9562). Dentro del mismo milisegundo, un contador de 12
 * bits en {@code rand_a} mantiene los valores monotónicos (RFC 9562 sección 6.2, método 1); si el
 * reloj retrocede, se reutiliza la última marca de tiempo para preservar el orden.
 *
 * <p>{@link #generate()} usa un generador compartido sobre el reloj del sistema en UTC; {@link
 * #generator(Clock)} crea un generador independiente, por ejemplo para pruebas.
 */
public final class UuidV7 implements IdGenerator {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int COUNTER_MAX = 0xFFF;
  private static final UuidV7 DEFAULT = new UuidV7(Clock.systemUTC());

  private final Clock clock;
  // La marca de tiempo (48 bits) y el contador monotónico (12 bits) se reservan en una única
  // operación CAS.
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

  /** Siguiente UUIDv7 de este generador. */
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
              : previous + 1; // El desborde del contador toma el milisegundo siguiente sin esperar.
    } while (!sequence.compareAndSet(previous, reserved));
    long millis = reserved >>> 12;
    int seq = (int) (reserved & COUNTER_MAX);
    long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
    msb |= 0x7000L; // versión 7
    msb |= seq & COUNTER_MAX;
    long lsb = RANDOM.nextLong();
    lsb &= 0x3FFF_FFFF_FFFF_FFFFL;
    lsb |= 0x8000_0000_0000_0000L; // variante IETF
    return new UUID(msb, lsb);
  }

  @Override
  public String newId() {
    return next().toString();
  }

  public static boolean isV7(UUID uuid) {
    return uuid.version() == 7 && uuid.variant() == 2;
  }

  /** Instante de creación incluido en un UUIDv7. */
  public static Instant timestamp(UUID uuid) {
    if (!isV7(uuid)) {
      throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
    }
    return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
  }
}
