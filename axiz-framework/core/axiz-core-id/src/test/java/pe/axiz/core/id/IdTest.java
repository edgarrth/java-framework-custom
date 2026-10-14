package pe.axiz.core.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdTest {

  @Test
  void uuidV7IsVersionedAndCarriesTimestamp() {
    Instant now = Instant.parse("2030-01-01T00:00:00.123Z");
    UUID id = UuidV7.generator(Clock.fixed(now, ZoneOffset.UTC)).next();
    assertEquals(7, id.version());
    assertEquals(2, id.variant());
    assertTrue(UuidV7.isV7(id));
    assertEquals(now, UuidV7.timestamp(id));
    assertThrows(IllegalArgumentException.class, () -> UuidV7.timestamp(UUID.randomUUID()));
  }

  @Test
  void uuidV7IsMonotonicWithinSameMillisecond() {
    UuidV7 generator =
        UuidV7.generator(Clock.fixed(Instant.parse("2031-06-01T00:00:00Z"), ZoneOffset.UTC));
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5000; i++) {
      ids.add(generator.next());
    }
    for (int i = 1; i < ids.size(); i++) {
      assertTrue(
          ids.get(i - 1).toString().compareTo(ids.get(i).toString()) < 0, "not monotonic at " + i);
    }
    assertEquals(5000, new HashSet<>(ids).size());
  }

  @Test
  void uuidV7UniqueAcrossVirtualThreads() throws Exception {
    Set<String> ids = ConcurrentHashMap.newKeySet();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      IntStream.range(0, 20_000)
          .forEach(i -> executor.submit(() -> ids.add(IdGenerator.uuidV7().newId())));
    }
    assertEquals(20_000, ids.size());
  }

  @Test
  void ulidFormatOrderAndTimestamp() {
    Instant t = Instant.parse("2026-02-03T04:05:06.789Z");
    Ulid generator = Ulid.generator(Clock.fixed(t, ZoneOffset.UTC));
    String a = generator.newId();
    String b = generator.newId();
    assertEquals(26, a.length());
    assertTrue(Ulid.isValid(a));
    assertTrue(a.compareTo(b) < 0);
    assertEquals(t, Ulid.timestamp(a));
    assertTrue(Ulid.isValid(IdGenerator.ulid().newId()));
    assertFalse(Ulid.isValid("8ZZZZZZZZZZZZZZZZZZZZZZZZZ"));
    assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAI"));
    assertThrows(IllegalArgumentException.class, () -> Ulid.timestamp("bad"));
  }

  @Test
  void clockGoingBackwardsKeepsOrder() {
    MutableClock clock = new MutableClock(Instant.parse("2030-01-01T00:00:01Z"));
    UuidV7 generator = UuidV7.generator(clock);
    UUID first = generator.next();
    clock.set(Instant.parse("2030-01-01T00:00:00Z"));
    UUID second = generator.next();
    assertTrue(first.toString().compareTo(second.toString()) < 0);
    assertEquals(UuidV7.timestamp(first), UuidV7.timestamp(second));
  }

  @Test
  void correlationIds() {
    assertTrue(CorrelationIds.isValid("abc-1234-xyz"));
    assertFalse(CorrelationIds.isValid("abc\n1234567"));
    assertFalse(CorrelationIds.isValid("short"));
    assertEquals("abc-1234-xyz", CorrelationIds.reuseOrCreate("abc-1234-xyz"));
    String generated = CorrelationIds.reuseOrCreate("bad value with spaces");
    assertTrue(UuidV7.isV7(UUID.fromString(generated)));
  }

  @Test
  void idempotencyKeys() {
    assertEquals(
        IdempotencyKeys.derive("payout", "B1", "7"), IdempotencyKeys.derive("payout", "B1", "7"));
    assertNotEquals(IdempotencyKeys.derive("ab", "c"), IdempotencyKeys.derive("a", "bc"));
    assertEquals(43, IdempotencyKeys.derive("x").length());
    assertTrue(IdempotencyKeys.isValid(IdempotencyKeys.newKey()));
    assertFalse(IdempotencyKeys.isValid("tiny"));
    assertThrows(IllegalArgumentException.class, IdempotencyKeys::derive);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant value) {
      now = value;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
