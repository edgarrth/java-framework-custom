package pe.axiz.spring.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.resilience.TimeoutSettings;

class HttpClientTest {

  private static ClientSettings settings(int maxConcurrent) {
    return new ClientSettings(
        "bank",
        URI.create("https://bank.example.com"),
        new TimeoutSettings(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10)),
        AuthSettings.none(),
        ClientSettings.RetryMode.IDEMPOTENT_ONLY,
        3,
        "default",
        maxConcurrent,
        List.of("X-Channel"),
        false,
        10);
  }

  @Test
  void onlyAllowListedHeadersArePropagated() {
    RequestMetadata m =
        RequestMetadata.builder("corr-12345678", "req-12345678")
            .locale(Locale.forLanguageTag("es-PE"))
            .tenantId("bank-a")
            .attributes(Map.of("X-Channel", "mobile", "X-Internal", "secret"))
            .build();
    Map<String, String> out = OutboundHeaders.of(m, settings(0), "X-Tenant-Id");
    assertEquals("corr-12345678", out.get("X-Correlation-Id"));
    assertEquals("mobile", out.get("X-Channel"));
    assertEquals("es-PE", out.get("Accept-Language"));
    assertEquals("bank-a", out.get("X-Tenant-Id"));
    assertFalse(out.containsKey("X-Internal"));
  }

  @Test
  void retriesOnlyIdempotentCallsAndRespectsBulkhead() {
    ClientResilience r = new ClientResilience(settings(1), CircuitBreakerRegistry.ofDefaults());
    long now = System.nanoTime();
    assertTrue(r.retry("GET", null, null, 503, 1, now));
    assertFalse(r.retry("POST", null, null, 503, 1, now));
    assertTrue(r.retry("POST", "key-1", null, 503, 1, now));
    assertFalse(r.retry("GET", null, null, 400, 1, now));
    assertFalse(r.retry("GET", null, null, 503, 3, now));
    r.acquire();
    assertThrows(RuntimeException.class, r::acquire);
    r.release(now, null, 200);
    r.acquire();
  }

  @Test
  void invalidClientSettingsFailAtStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizHttpClientAutoConfiguration.class))
        .withPropertyValues("axiz.http-client.clients.Bad_Name.base-url=https://x.example.com")
        .run(ctx -> assertTrue(ctx.getStartupFailure() != null));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizHttpClientAutoConfiguration.class))
        .withPropertyValues("axiz.http-client.clients.bank.base-url=https://bank.example.com")
        .run(ctx -> assertTrue(ctx.getBean(HttpClientRegistry.class).find("bank").isPresent()));
  }
}
