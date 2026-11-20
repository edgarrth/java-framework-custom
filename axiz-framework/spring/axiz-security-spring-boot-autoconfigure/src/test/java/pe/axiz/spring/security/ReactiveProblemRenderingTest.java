package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.ErrorCatalog;
import pe.axiz.core.errors.ProblemFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ReactiveProblemRenderingTest {
  @ParameterizedTest
  @ValueSource(strings = {"60", "3", "0", "-1", "invalid", "999999999999999999999"})
  void saturationPreservesTheLongerQuotaOrIssuerPause(String retryAfter) {
    var scheduler = Schedulers.newBoundedElastic(1, 1, "quota-saturated");
    scheduler.dispose();
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/limited"));
    exchange.getResponse().getHeaders().set("Retry-After", retryAfter);
    ReactiveProblemHandlers.write(
            scheduler,
            exchange,
            HttpStatus.TOO_MANY_REQUESTS,
            () -> {
              throw new AssertionError("Resolver must not run on rejection");
            })
        .block(Duration.ofSeconds(5));
    assertEquals(503, exchange.getResponse().getStatusCode().value());
    assertEquals(
        List.of("60", "3").contains(retryAfter) ? retryAfter : "1",
        exchange.getResponse().getHeaders().getFirst("Retry-After"));
    assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
    assertEquals(
        "application/problem+json",
        exchange.getResponse().getHeaders().getContentType().toString());
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 429, 503})
  void saturatedRenderingReturnsStatic503WithoutCallingResolver(int status) {
    var scheduler = Schedulers.newBoundedElastic(1, 1, "saturated-security");
    scheduler.dispose();
    var calls = new AtomicInteger();
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/restricted"));
    exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
    ReactiveProblemHandlers.write(
            scheduler,
            exchange,
            HttpStatus.valueOf(status),
            () -> {
              calls.incrementAndGet();
              return "must not render";
            })
        .block(Duration.ofSeconds(5));
    assertEquals(503, exchange.getResponse().getStatusCode().value());
    assertEquals(0, calls.get());
    assertNull(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"));
    assertEquals("1", exchange.getResponse().getHeaders().getFirst("Retry-After"));
    assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"status\":503");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void securityRenderingNeverInheritsResidualIdentityOrMdc(boolean explicitContext) {
    var scheduler = Schedulers.immediate();
    var previous = RequestContext.get();
    var previousMdc = MDC.getCopyOfContextMap();
    var stale = RequestMetadata.builder("stale", "stale-request").build();
    var request =
        RequestMetadata.builder(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002")
            .build();
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/restricted"));
    try {
      RequestContext.restore(stale);
      MDC.setContextMap(Map.of("traceId", "0123456789abcdef", "private", "residual"));
      var response =
          ReactiveProblemHandlers.write(
              scheduler,
              exchange,
              HttpStatus.UNAUTHORIZED,
              () -> {
                assertEquals(explicitContext ? request : null, RequestContext.get());
                assertNull(MDC.get("private"));
                assertNull(MDC.get("traceId"));
                assertEquals(
                    explicitContext ? request.correlationId() : null, MDC.get("correlationId"));
                return "{}";
              });
      if (explicitContext)
        response = response.contextWrite(context -> context.put(RequestContext.KEY, request));
      response.block(Duration.ofSeconds(5));
      assertEquals(stale, RequestContext.get());
      assertEquals("residual", MDC.get("private"));
    } finally {
      RequestContext.restore(previous);
      if (previousMdc == null) MDC.clear();
      else MDC.setContextMap(previousMdc);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 503})
  void localizedSecurityErrorsLeaveTheEventLoopAndPreserveRequestContext(int status) {
    var metadata =
        new RequestMetadata(
            "render-correlation",
            "render-request",
            null,
            null,
            null,
            null,
            null,
            Locale.ENGLISH,
            Instant.EPOCH,
            Map.of());
    var calls = new AtomicInteger();
    var factory =
        new ProblemFactory(
            ErrorCatalog.defaults(),
            (key, locale, parameters) -> {
              assertFalse(
                  Schedulers.isInNonBlockingThread(),
                  "Synchronous message resolvers may read bundles");
              assertEquals(metadata, RequestContext.get());
              calls.incrementAndGet();
              return Optional.of("Localized security response");
            },
            List.of(),
            ProblemFactory.Settings.defaults(),
            Clock.systemUTC());
    var handler =
        new ReactiveProblemHandlers(new SecurityProblems(factory), Schedulers.boundedElastic());
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/restricted"));
    Mono.defer(
            () ->
                switch (status) {
                  case 401 -> handler.commence(exchange, new BadCredentialsException("synthetic"));
                  case 403 -> handler.handle(exchange, new AccessDeniedException("synthetic"));
                  default -> handler.commence(exchange, new IssuerUnavailableException(3));
                })
        .contextWrite(context -> context.put(RequestContext.KEY, metadata))
        .subscribeOn(Schedulers.parallel())
        .block(Duration.ofSeconds(5));
    assertEquals(status, exchange.getResponse().getStatusCode().value());
    assertEquals(1, calls.get());
    assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5)))
        .contains("Localized security response", "render-correlation");
    if (status == 503)
      assertEquals("3", exchange.getResponse().getHeaders().getFirst("Retry-After"));
  }
}
