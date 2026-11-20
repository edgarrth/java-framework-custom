package pe.axiz.spring.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ErrorMessageResolver;
import pe.axiz.core.errors.RateLimitedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class ReactiveRenderingIsolationTest {
  private static final Duration WAIT = Duration.ofSeconds(5);

  @ParameterizedTest
  @ValueSource(longs = {0, 1, 60, 86401})
  void saturatedRenderingPreservesTypedRateLimitDelayWithoutInvokingResolver(long seconds) {
    var scheduler = Schedulers.newBoundedElastic(1, 1, "limited-render-test");
    scheduler.dispose();
    var limited =
        new RateLimitedException(CoreErrors.TOO_MANY_REQUESTS, Duration.ofSeconds(seconds));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
        .withBean(
            ErrorMessageResolver.class,
            () ->
                (key, locale, parameters) -> {
                  throw new AssertionError("Rejected work must not run inline");
                })
        .run(
            context -> {
              var handler =
                  new ProblemWebExceptionHandler(
                      context.getBean(ProblemResponder.class), scheduler);
              for (Throwable failure : List.of(limited, new IllegalStateException(limited))) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/limited"));
                handler.handle(exchange, failure).block(WAIT);
                assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
                assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                    .isEqualTo(Long.toString(Math.max(1, Math.min(86400, seconds))));
                assertThat(exchange.getResponse().getHeaders().getCacheControl())
                    .isEqualTo("no-store");
                assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                    .isEqualTo("application/problem+json");
              }
            });
  }

  @Test
  void neverCopiesSubscriberMdcEvenWhenResidualTraceIdsHaveAValidFormat() {
    var scheduler = Schedulers.newBoundedElastic(1, 4, "render-tracing-test");
    var before = MDC.getCopyOfContextMap();
    String span = "0123456789abcdef";
    try {
      Mono.fromRunnable(
              () -> MDC.setContextMap(Map.of("traceId", "worker-stale", "other", "worker")))
          .subscribeOn(scheduler)
          .block(WAIT);
      for (var tracing :
          List.of(
              Map.of("traceId", span, "spanId", span),
              Map.of("traceId", span + span, "spanId", span),
              Map.of("traceId", "invalid\ntrace", "spanId", span),
              Map.of("traceId", span, "spanId", "invalid\nspan"),
              Map.of("traceId", span),
              Map.of("spanId", span))) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
            .withBean(
                ErrorMessageResolver.class,
                () ->
                    (key, locale, parameters) -> {
                      assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                      return Optional.of("localized");
                    })
            .run(
                context -> {
                  MDC.setContextMap(tracing);
                  MDC.put("other", "subscriber-private-data");
                  new ProblemWebExceptionHandler(context.getBean(ProblemResponder.class), scheduler)
                      .handle(
                          MockServerWebExchange.from(MockServerHttpRequest.get("/test")),
                          new BusinessException(CoreErrors.CONFLICT, "synthetic"))
                      .block(WAIT);
                  assertThat(MDC.get("other")).isEqualTo("subscriber-private-data");
                  Mono.fromRunnable(
                          () ->
                              assertThat(MDC.getCopyOfContextMap())
                                  .containsExactlyInAnyOrderEntriesOf(
                                      Map.of("traceId", "worker-stale", "other", "worker")))
                      .subscribeOn(scheduler)
                      .block(WAIT);
                });
      }
    } finally {
      Mono.fromRunnable(MDC::clear).subscribeOn(scheduler).block(WAIT);
      scheduler.dispose();
      if (before == null) MDC.clear();
      else MDC.setContextMap(before);
    }
  }

  @Test
  void exchangeAndSubscriberContextAreScopedAndRestoredEvenWhenResolverThrows() {
    var scheduler = Schedulers.newBoundedElastic(1, 4, "render-context-test");
    var prior = RequestMetadata.builder("previous-correlation", "previous-request").build();
    var metadata =
        RequestMetadata.builder("request-correlation", "request-id").tenantId("tenant-a").build();
    try {
      Mono.fromRunnable(
              () -> {
                RequestContext.restore(prior);
                MDC.setContextMap(Map.of("correlationId", "previous", "unrelated", "preserve"));
              })
          .subscribeOn(scheduler)
          .block(WAIT);
      for (boolean failing : List.of(false, true)) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
            .withBean(
                ErrorMessageResolver.class,
                () ->
                    (key, locale, parameters) -> {
                      assertFalse(Schedulers.isInNonBlockingThread());
                      assertThat(RequestContext.get()).isEqualTo(metadata);
                      assertThat(MDC.getCopyOfContextMap())
                          .containsExactlyInAnyOrderEntriesOf(
                              Map.of(
                                  "correlationId",
                                  "request-correlation",
                                  "requestId",
                                  "request-id"));
                      if (failing) throw new IllegalStateException("resolver failed");
                      return Optional.of("localized");
                    })
            .run(
                context -> {
                  var handler =
                      new ProblemWebExceptionHandler(
                          context.getBean(ProblemResponder.class), scheduler);
                  for (boolean subscriber : List.of(false, true)) {
                    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test"));
                    exchange.getAttributes().put(RequestContext.KEY, subscriber ? prior : metadata);
                    Mono<Void> result =
                        handler.handle(
                            exchange, new BusinessException(CoreErrors.CONFLICT, "synthetic"));
                    if (subscriber)
                      result = result.contextWrite(c -> c.put(RequestContext.KEY, metadata));
                    if (failing) {
                      Mono<Void> operation = result;
                      assertThrows(IllegalStateException.class, () -> operation.block(WAIT));
                    } else {
                      result.subscribeOn(Schedulers.parallel()).block(WAIT);
                      assertThat(exchange.getResponse().getBodyAsString().block(WAIT))
                          .contains("request-correlation", "localized");
                    }
                    Mono.fromRunnable(
                            () -> {
                              assertThat(RequestContext.get()).isEqualTo(prior);
                              assertThat(MDC.getCopyOfContextMap())
                                  .isEqualTo(
                                      Map.of("correlationId", "previous", "unrelated", "preserve"));
                            })
                        .subscribeOn(scheduler)
                        .block(WAIT);
                  }
                });
      }
    } finally {
      Mono.fromRunnable(
              () -> {
                RequestContext.restore(null);
                MDC.clear();
              })
          .subscribeOn(scheduler)
          .block(WAIT);
      scheduler.dispose();
    }
  }

  @Test
  void saturationRejectsWithoutRunningResolverOnCallerAndSharedWorkersRemainAvailable()
      throws Exception {
    var scheduler = Schedulers.newBoundedElastic(1, 1, "saturated-render-test");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      scheduler.schedule(
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      scheduler.schedule(() -> {});
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
          .withBean(
              ErrorMessageResolver.class,
              () ->
                  (key, locale, parameters) -> {
                    throw new AssertionError("Rejected work must not run inline");
                  })
          .run(
              context -> {
                var handler =
                    new ProblemWebExceptionHandler(
                        context.getBean(ProblemResponder.class), scheduler);
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test"));
                handler
                    .handle(exchange, new BusinessException(CoreErrors.CONFLICT, "synthetic"))
                    .block(WAIT);
                assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
                assertThat(exchange.getResponse().getHeaders().getCacheControl())
                    .isEqualTo("no-store");
                assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                    .isEqualTo("1");
                assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                    .isEqualTo("application/problem+json");
                assertThat(exchange.getResponse().getBodyAsString().block(WAIT))
                    .contains("Service Unavailable");
                assertThat(
                        Mono.just("available").subscribeOn(Schedulers.boundedElastic()).block(WAIT))
                    .isEqualTo("available");
              });
    } finally {
      release.countDown();
      scheduler.dispose();
    }
  }

  @Test
  void starterOwnsAnIsolatedSchedulerAndDisposesItWithTheContext() {
    var actual = new java.util.concurrent.atomic.AtomicReference<Scheduler>();
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
        .run(
            context -> {
              var scheduler = context.getBean("axizErrorRenderingScheduler", Scheduler.class);
              actual.set(scheduler);
              assertThat(scheduler).isNotSameAs(Schedulers.boundedElastic());
              assertThat(
                      Mono.fromSupplier(() -> Thread.currentThread().getName())
                          .subscribeOn(scheduler)
                          .block(WAIT))
                  .startsWith("axiz-error-render");
            });
    assertTrue(actual.get().isDisposed());
  }
}
