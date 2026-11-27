package pe.axiz.spring.tokenization.vault.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Limits;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Windows;
import pe.axiz.spring.tokenization.vault.redis.AxizTokenizationRedisProperties.Redis;

class TokenRedisBootstrapTest {
  @Test
  void rejectsInsecureTransportBeforeResolvingSecretsOrOpeningAConnection() {
    var settings =
        new Redis("localhost", 6379, false, "vault", "budget/password", Duration.ofMillis(100), 10);
    var resolutions = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            TokenRedisResources.open(
                settings,
                new DataTransportSecurityPolicy("production", true),
                name -> {
                  resolutions.incrementAndGet();
                  throw new IllegalStateException("must not resolve");
                }));
    assertEquals(0, resolutions.get());
  }

  @Test
  void rejectsFailOpenMissingPolicyAndUnboundedTransport() {
    var windows = new Windows(1, 10, 100);
    var limits = new Limits(windows, windows, windows);
    var policy =
        TokenBudgetPolicy.builder()
            .query(limits)
            .reveal(limits)
            .metadata(limits)
            .writes(limits)
            .subjectConcurrent(1)
            .clientConcurrent(2)
            .tenantConcurrent(3)
            .concurrencyRetryAfter(Duration.ofSeconds(1))
            .build();
    var settings =
        new Redis("redis", 6379, true, "vault", "budget/password", Duration.ofMillis(100), 10);
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizTokenizationRedisProperties("vault", policy, settings, true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Redis(
                "redis://user:secret@host", 6379, true, "vault", "ref", Duration.ofSeconds(1), 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Redis("redis", 6379, true, "vault", "ref", Duration.ofSeconds(4), 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Redis("redis", 6379, true, "vault", "ref", Duration.ofSeconds(1), 10001));
  }

  @Test
  void rejectsMissingNamesCredentialsAndUnboundedQueuesBeforeIO() {
    for (String host : new String[] {null, "", "x".repeat(254), "redis/password"})
      assertThrows(
          IllegalArgumentException.class,
          () -> new Redis(host, 6379, true, "vault", "ref", Duration.ofSeconds(1), 1));
    for (int port : new int[] {0, 65536})
      assertThrows(
          IllegalArgumentException.class,
          () -> new Redis("redis", port, true, "vault", "ref", Duration.ofSeconds(1), 1));
    for (String user : new String[] {null, "", "bad user"})
      assertThrows(
          IllegalArgumentException.class,
          () -> new Redis("redis", 6379, true, user, "ref", Duration.ofSeconds(1), 1));
    for (String reference : new String[] {null, "", "bad reference"})
      assertThrows(
          IllegalArgumentException.class,
          () -> new Redis("redis", 6379, true, "vault", reference, Duration.ofSeconds(1), 1));
    for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofNanos(999999)})
      assertThrows(
          IllegalArgumentException.class,
          () -> new Redis("redis", 6379, true, "vault", "ref", timeout, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Redis("redis", 6379, true, "vault", "ref", Duration.ofSeconds(1), 0));
    var valid = new Redis("redis", 65535, true, "vault", "ref", Duration.ofSeconds(3), 10000);
    assertEquals(10000, valid.queueCapacity());
    for (String namespace : new String[] {null, "", "INVALID"})
      assertThrows(
          IllegalArgumentException.class,
          () -> new AxizTokenizationRedisProperties(namespace, null, valid, false));
    assertThrows(
        NullPointerException.class,
        () -> new AxizTokenizationRedisProperties("vault", null, valid, false));
  }

  @Test
  void credentialsUseOwnedCopiesMaskTheirRepresentationAndCloseOnShutdownOrInvalidLength() {
    var secret = SecretValue.of("synthetic-password-canary");
    try (var credentials = new TokenRedisCredentials("vault", secret)) {
      assertFalse(credentials.toString().contains("canary"));
      var copy = credentials.getPassword();
      Arrays.fill(copy, '\0');
      assertArrayEquals("synthetic-password-canary".toCharArray(), credentials.getPassword());
      assertEquals(credentials, credentials.resolveCredentialsNow());
    }
    assertThrows(IllegalStateException.class, secret::revealChars);
    var empty = SecretValue.of("");
    assertThrows(IllegalArgumentException.class, () -> new TokenRedisCredentials("vault", empty));
    assertThrows(IllegalStateException.class, empty::revealChars);
  }
}
