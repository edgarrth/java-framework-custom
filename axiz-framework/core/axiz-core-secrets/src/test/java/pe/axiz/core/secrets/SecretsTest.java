package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecretsTest {

  @Test
  void secretValueNeverPrints() {
    SecretValue s = SecretValue.of("p4ss");
    assertEquals("SecretValue[****]", s.toString());
    assertEquals("p4ss", s.reveal());
    assertEquals(4, s.revealChars().length);
    assertTrue(s.matches(SecretValue.of("p4ss")));
    assertNotEquals(SecretValue.of("p4ss"), s);
    assertNotEquals(SecretValue.of("other"), s);
    char[][] temporary = new char[1][];
    assertEquals(
        "p4ss",
        s.use(
            chars -> {
              temporary[0] = chars;
              return new String(chars);
            }));
    assertTrue(java.util.Arrays.equals(new char[4], temporary[0]));
    s.close();
    s.close();
    assertThrows(IllegalStateException.class, s::reveal);
    assertThrows(IllegalStateException.class, s::revealChars);
    assertThrows(IllegalStateException.class, () -> s.use(chars -> chars.length));
  }

  @Test
  void cachingHonorsTtl() {
    AtomicInteger calls = new AtomicInteger();
    Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0];
          }
        };
    CachingSecretResolver cache =
        new CachingSecretResolver(
            name -> {
              calls.incrementAndGet();
              return name.equals("a")
                  ? Optional.of(SecretValue.of("v" + calls.get()))
                  : Optional.empty();
            },
            Duration.ofMinutes(5),
            clock);
    assertEquals("v1", cache.require("a").reveal());
    assertEquals("v1", cache.require("a").reveal());
    now[0] = now[0].plus(Duration.ofMinutes(6));
    assertEquals("v2", cache.require("a").reveal());
    cache.evict("a");
    assertEquals("v3", cache.require("a").reveal());
    assertTrue(cache.resolve("b").isEmpty());
    assertTrue(cache.resolve("b").isEmpty());
    assertEquals(5, calls.get());
  }
}
