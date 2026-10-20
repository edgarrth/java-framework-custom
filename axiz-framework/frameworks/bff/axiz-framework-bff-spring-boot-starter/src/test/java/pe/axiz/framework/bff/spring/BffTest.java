package pe.axiz.framework.bff.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.framework.bff.Composition;
import reactor.core.publisher.Mono;

class BffTest {

  @Test
  void requestContextReachesParallelParts() {
    ContextRegistry registry = new ContextRegistry();
    registry.registerThreadLocalAccessor(
        RequestContext.KEY,
        RequestContext::get,
        RequestContext::restore,
        () -> RequestContext.restore(null));
    Compositions compositions =
        new Compositions(
            ContextSnapshotFactory.builder().contextRegistry(registry).build(),
            Duration.ofSeconds(2));
    try (RequestContext.Scope s =
        RequestContext.open(RequestMetadata.builder("corr-12345678", "req-12345678").build())) {
      Composition c =
          compositions.compose().required("who", () -> RequestContext.get().correlationId()).run();
      assertEquals("corr-12345678", c.get("who", String.class));
    }
  }

  @Test
  void reactiveOptionalPartsFallBack() {
    Composition c =
        ReactiveComposer.within(Duration.ofMillis(200))
            .required("account", Mono.just("acc"))
            .optional(
                "offers", Mono.<List<String>>error(new IllegalStateException("down")), List::of)
            .run()
            .block();
    assertEquals("acc", c.get("account", String.class));
    assertEquals(List.of("offers"), c.degraded());
  }

  @Test
  void theConfiguredTimeoutBoundsAComposition() {
    // "50ms" is converted as Spring Boot does, also outside a SpringApplication
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizBffAutoConfiguration.class))
        .withPropertyValues("axiz.bff.timeout=50ms")
        .run(
            ctx -> {
              Compositions compositions = ctx.getBean(Compositions.class);
              long start = System.nanoTime();
              assertThrows(
                  ExternalServiceException.class,
                  () ->
                      compositions
                          .compose()
                          .required(
                              "slow",
                              () -> {
                                Thread.sleep(2_000);
                                return "late";
                              })
                          .run());
              assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1_500);
            });
  }
}
