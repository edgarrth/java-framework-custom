package pe.axiz.spring.metrics;

import io.micrometer.registry.otlp.ExemplarContextProvider;
import io.micrometer.registry.otlp.OtlpExemplarContext;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;

/**
 * Las métricas del bootstrap no tienen exemplar de traza; después del bootstrap solo se consulta el
 * tracer cacheado.
 */
final class StartupExemplarContextProvider
    implements ExemplarContextProvider, SmartInitializingSingleton {
  private final ObjectProvider<Tracer> tracers;
  private final TracingProperties.Exemplars.Include include;
  private volatile @Nullable Tracer tracer;

  StartupExemplarContextProvider(
      ObjectProvider<Tracer> tracers, TracingProperties.Exemplars.Include include) {
    this.tracers = tracers;
    this.include = include;
  }

  @Override
  public void afterSingletonsInstantiated() {
    tracer = tracers.getObject();
  }

  @Override
  public @Nullable OtlpExemplarContext getExemplarContext() {
    var current = tracer;
    if (current == null || include == TracingProperties.Exemplars.Include.NONE) return null;
    var span = current.currentSpan();
    if (span == null) return null;
    var context = span.context();
    if (include == TracingProperties.Exemplars.Include.SAMPLED_TRACES
        && !Boolean.TRUE.equals(context.sampled())) return null;
    return new OtlpExemplarContext(context.traceId(), context.spanId());
  }
}
