package pe.axiz.spring.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.resilience.TimeoutSettings;
import reactor.core.publisher.Mono;

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

  @Test
  void reactiveTokenRelaySendsTheInboundTokenFromTheReactorContext() {
    ClientSettings relay =
        new ClientSettings(
            "crud",
            URI.create("https://crud.example.com"),
            new TimeoutSettings(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10)),
            new AuthSettings(AuthType.TOKEN_RELAY, null, null, null, List.of(), null, null, null),
            ClientSettings.RetryMode.IDEMPOTENT_ONLY,
            3,
            "default",
            0,
            List.of(),
            false,
            10);
    AxizExchangeFilter filter =
        new AxizExchangeFilter(
            new ClientResilience(relay, CircuitBreakerRegistry.ofDefaults()),
            OutboundAuth.create(relay, null, Duration.ofSeconds(30), Clock.systemUTC()),
            null);
    AtomicReference<ClientRequest> sent = new AtomicReference<>();
    ExchangeFunction next =
        req -> {
          sent.set(req);
          return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
    ClientRequest request =
        ClientRequest.create(HttpMethod.GET, URI.create("https://crud.example.com/v1/x")).build();
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "inbound-token",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(300));
    BearerTokenAuthentication inbound =
        new BearerTokenAuthentication(
            new DefaultOAuth2AuthenticatedPrincipal(Map.of("sub", "C-1001"), List.of()),
            token,
            List.of());

    filter
        .filter(request, next)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(inbound))
        .block(Duration.ofSeconds(5));
    assertEquals("Bearer inbound-token", sent.get().headers().getFirst("Authorization"));

    // without an inbound token nothing is relayed
    filter.filter(request, next).block(Duration.ofSeconds(5));
    assertNull(sent.get().headers().getFirst("Authorization"));
  }

  @Test
  void reactiveTransportFailuresBecomeDependencyErrors() {
    AxizExchangeFilter filter =
        new AxizExchangeFilter(
            new ClientResilience(settings(0), CircuitBreakerRegistry.ofDefaults()),
            OutboundAuth.create(settings(0), null, Duration.ofSeconds(30), Clock.systemUTC()),
            null);
    ExchangeFunction refused = req -> Mono.error(new java.net.ConnectException("refused"));
    ClientRequest request =
        ClientRequest.create(HttpMethod.POST, URI.create("https://bank.example.com/v1/x")).build();
    pe.axiz.core.errors.ExternalServiceException e =
        assertThrows(
            pe.axiz.core.errors.ExternalServiceException.class,
            () -> filter.filter(request, refused).block(Duration.ofSeconds(5)));
    assertEquals(503, e.category().httpStatus());
    assertEquals("bank", e.detail().system());
    assertTrue(e.getCause() instanceof java.net.ConnectException);
  }

  @Test
  void servletTimeoutIsAGatewayTimeoutThatKeepsItsCause() {
    AxizRestClientInterceptor interceptor =
        new AxizRestClientInterceptor(
            new ClientResilience(settings(0), CircuitBreakerRegistry.ofDefaults()),
            OutboundAuth.create(settings(0), null, Duration.ofSeconds(30), Clock.systemUTC()),
            null);
    org.springframework.mock.http.client.MockClientHttpRequest request =
        new org.springframework.mock.http.client.MockClientHttpRequest(
            HttpMethod.POST, URI.create("https://bank.example.com/v1/debits"));
    java.io.IOException e =
        assertThrows(
            java.io.IOException.class,
            () ->
                interceptor.intercept(
                    request,
                    new byte[0],
                    (req, body) -> {
                      throw new java.net.SocketTimeoutException("Read timed out");
                    }));
    pe.axiz.core.errors.ExternalServiceException dependency =
        (pe.axiz.core.errors.ExternalServiceException) e.getCause();
    assertEquals(504, dependency.category().httpStatus());
    assertEquals("Call to bank got no response (SocketTimeoutException)", dependency.getMessage());
    assertTrue(dependency.getCause() instanceof java.net.SocketTimeoutException);
  }
}
