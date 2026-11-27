package pe.axiz.core.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class UlidBoundaryTest {
  private static RandomGenerator entropy(int high, long low) {
    return new RandomGenerator() {
      public long nextLong() {
        return low;
      }

      public int nextInt(int bound) {
        assertEquals(65536, bound);
        return high;
      }
    };
  }

  @Test
  void lowWordCarryAndHighWordBoundariesAreMonotonicForEveryInitialEntropy() {
    for (int high : new int[] {0, 1, 32767, 60000, 65534}) {
      var generator = new Ulid(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), entropy(high, -2L));
      String before = generator.newId();
      String lastLow = generator.newId();
      String carry = generator.newId();
      String next = generator.newId();
      assertTrue(before.compareTo(lastLow) < 0);
      assertTrue(lastLow.compareTo(carry) < 0);
      assertTrue(carry.compareTo(next) < 0);
      assertEquals(Instant.EPOCH, Ulid.timestamp(carry));
      assertTrue(Ulid.isValid(carry));
    }
  }

  @Test
  void fullEntropyOverflowIsRejectedRatherThanReusingAnId() {
    var generator =
        new Ulid(
            Clock.fixed(Instant.ofEpochMilli(0xFFFFFFFFFFFFL), ZoneOffset.UTC),
            entropy(65535, -1L));
    String last = generator.newId();
    assertEquals(Instant.ofEpochMilli(0xFFFFFFFFFFFFL), Ulid.timestamp(last));
    assertThrows(IllegalStateException.class, generator::newId);
    assertThrows(IllegalStateException.class, generator::newId);
  }
}
