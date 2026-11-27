package pe.axiz.spring.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import reactor.util.context.Context;

class ReactiveRenderContextTest {
  @Test
  void missingReactiveContextNeverInheritsThreadIdentityOrTracing() {
    var previous = RequestContext.get();
    var mdc = MDC.getCopyOfContextMap();
    var stale = RequestMetadata.builder("stale", "stale-request").build();
    try {
      RequestContext.restore(stale);
      MDC.setContextMap(Map.of("traceId", "0123456789abcdef", "private", "residual"));
      assertEquals(
          "rendered",
          ReactiveRenderContext.call(
              Context.empty(),
              null,
              () -> {
                assertNull(RequestContext.get());
                assertNull(MDC.get("traceId"));
                assertNull(MDC.get("private"));
                return "rendered";
              }));
      assertEquals(stale, RequestContext.get());
      assertEquals("residual", MDC.get("private"));
    } finally {
      RequestContext.restore(previous);
      if (mdc == null) MDC.clear();
      else MDC.setContextMap(mdc);
    }
  }

  @Test
  void observationAndRequestScopesRestoreStateOnSuccessAndFailure() {
    var previous = RequestContext.get();
    var mdc = MDC.getCopyOfContextMap();
    var request =
        RequestMetadata.builder(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002")
            .build();
    var exchange =
        RequestMetadata.builder(
                "00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000004")
            .build();
    var registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onScopeOpened(Observation.Context context) {
                MDC.put("traceId", "request-trace");
              }

              @Override
              public void onScopeClosed(Observation.Context context) {
                MDC.remove("traceId");
              }
            });
    var observation = Observation.start("render", registry);
    try {
      for (boolean subscriber : List.of(false, true)) {
        RequestContext.restore(null);
        MDC.clear();
        var context = Context.of(ObservationThreadLocalAccessor.KEY, observation);
        if (subscriber) context = context.put(RequestContext.KEY, request);
        var expected = subscriber ? request : exchange;
        var reactive = context;
        assertThrows(
            IllegalStateException.class,
            () ->
                ReactiveRenderContext.call(
                    reactive,
                    exchange,
                    () -> {
                      assertEquals(expected, RequestContext.get());
                      assertEquals(expected.correlationId(), MDC.get("correlationId"));
                      assertEquals(expected.requestId(), MDC.get("requestId"));
                      assertEquals("request-trace", MDC.get("traceId"));
                      throw new IllegalStateException("resolver unavailable");
                    }));
        assertNull(RequestContext.get());
        assertNull(MDC.get("traceId"));
      }
    } finally {
      observation.stop();
      RequestContext.restore(previous);
      if (mdc == null) MDC.clear();
      else MDC.setContextMap(mdc);
    }
  }
}
