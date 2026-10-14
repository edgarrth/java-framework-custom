package pe.axiz.framework.bff.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
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
}
