package pe.axiz.spring.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** W3C {@code traceparent} propagation for the {@link SimpleTracer} of the tests. */
final class W3cTestPropagator implements Propagator {

  private final SimpleTracer tracer;

  W3cTestPropagator(SimpleTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public List<String> fields() {
    return List.of("traceparent");
  }

  @Override
  public <C> void inject(TraceContext context, @Nullable C carrier, Setter<C> setter) {
    setter.set(carrier, "traceparent", "00-" + context.traceId() + "-" + context.spanId() + "-01");
  }

  @Override
  public <C> Span.Builder extract(C carrier, Getter<C> getter) {
    Span.Builder builder = tracer.spanBuilder();
    String value = getter.get(carrier, "traceparent");
    if (value != null) {
      String[] parts = value.split("-");
      builder.setParent(
          tracer.traceContextBuilder().traceId(parts[1]).spanId(parts[2]).sampled(true).build());
    }
    return builder;
  }
}
