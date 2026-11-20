package pe.axiz.spring.foundation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.id.CorrelationIds;
import reactor.util.context.ContextView;

/** Scopes synchronous presentation work to the explicit reactive request and observation. */
public final class ReactiveRenderContext {
  private ReactiveRenderContext() {}

  public static <T> T call(
      ContextView context, @Nullable RequestMetadata exchangeMetadata, Supplier<T> render) {
    RequestMetadata metadata = context.getOrDefault(RequestContext.KEY, exchangeMetadata);
    Observation observation =
        context
            .<Observation>getOrEmpty(ObservationThreadLocalAccessor.KEY)
            .orElse(Observation.NOOP);
    RequestMetadata previous = RequestContext.get();
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    try {
      RequestContext.restore(metadata);
      MDC.clear();
      // Opening the request observation's scope restores its tracing handlers on this worker.
      // Neither the subscribing thread's MDC nor an arbitrary request header supplies trace IDs.
      try (Observation.Scope ignored = observation.openScope()) {
        if (metadata != null) {
          MDC.put("correlationId", CorrelationIds.reuseOrCreate(metadata.correlationId()));
          MDC.put("requestId", CorrelationIds.reuseOrCreate(metadata.requestId()));
        }
        return render.get();
      }
    } finally {
      RequestContext.restore(previous);
      if (previousMdc == null) MDC.clear();
      else MDC.setContextMap(previousMdc);
    }
  }
}
