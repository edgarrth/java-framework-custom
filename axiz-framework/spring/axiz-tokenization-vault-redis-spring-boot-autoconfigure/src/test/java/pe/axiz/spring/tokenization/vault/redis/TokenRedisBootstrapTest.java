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
