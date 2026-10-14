package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsTest {

  @Test
  void secretValueNeverPrints() {
    SecretValue s = SecretValue.of("p4ss");
    assertEquals("SecretValue[****]", s.toString());
    assertEquals("p4ss", s.reveal());
    assertEquals(4, s.revealChars().length);
    assertEquals(SecretValue.of("p4ss"), s);
    assertNotEquals(SecretValue.of("other"), s);
    assertEquals(SecretValue.of("p4ss").hashCode(), s.hashCode());
  }

  @Test
  void environmentAndFileResolvers(@TempDir Path dir) throws Exception {
    EnvironmentSecretResolver env =
        new EnvironmentSecretResolver(Map.of("PAYMENTS_DB_PASSWORD", "x", "EMPTY", ""));
    assertEquals("x", env.require("payments/db-password").reveal());
    assertTrue(env.resolve("empty").isEmpty());
    Files.writeString(dir.resolve("payments.api-key"), "key-1\n");
    FileSecretResolver files = new FileSecretResolver(dir);
    assertEquals("key-1", files.require("payments/api-key").reveal());
    assertTrue(files.resolve("missing").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> files.resolve("../etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> files.resolve("a b"));
    CompositeSecretResolver composite = new CompositeSecretResolver(List.of(env, files));
    assertEquals("key-1", composite.require("payments/api-key").reveal());
    SecretNotFoundException e =
        assertThrows(SecretNotFoundException.class, () -> composite.require("nope"));
    assertFalse(e.getMessage().contains("key-1"));
    assertTrue(EnvironmentSecretResolver.system() != null);
  }

  @Test
  void cachingHonorsTtl() {
    AtomicInteger calls = new AtomicInteger();
    Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
    Clock clock =
        new Clock() {
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
