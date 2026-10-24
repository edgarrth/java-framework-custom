package pe.axiz.spring.metrics;

import java.lang.reflect.Method;
import java.util.List;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.FailureClassifier;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.MetricSource;
import pe.axiz.core.metrics.Outcome;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reactor support for {@code @Metered}; loaded only when Reactor is present. */
final class ReactiveMetering {

  private ReactiveMetering() {}

  static boolean returnsReactive(Method method) {
    return Mono.class.isAssignableFrom(method.getReturnType())
        || Flux.class.isAssignableFrom(method.getReturnType());
  }

  static @Nullable Object measure(
      EventMetrics metrics,
      String event,
      @Nullable String category,
      boolean duration,
      List<?> sources,
      MethodInvocation invocation)
      throws Throwable {
    Object result = invocation.proceed();
    if (result instanceof Mono<?> mono) {
      return Mono.defer(
          () -> {
            long start = System.nanoTime();
            return mono.doOnSuccess(
                    v -> done(metrics, event, category, duration, sources, v, null, start))
                .doOnError(e -> done(metrics, event, category, duration, sources, null, e, start))
                .doOnCancel(() -> cancelled(metrics, event, category, duration, start));
          });
    }
    if (result instanceof Flux<?> flux) {
      return Flux.defer(
          () -> {
            long start = System.nanoTime();
            return flux.doOnComplete(
                    () -> done(metrics, event, category, duration, sources, null, null, start))
                .doOnError(e -> done(metrics, event, category, duration, sources, null, e, start))
                .doOnCancel(() -> cancelled(metrics, event, category, duration, start));
          });
    }
    return result;
  }

  private static void done(
      EventMetrics metrics,
      String event,
      @Nullable String category,
      boolean duration,
      List<?> sources,
      @Nullable Object value,
      @Nullable Throwable error,
      long start) {
    MetricEvent m = MetricEvent.of(event).category(category);
    for (Object s : sources) {
      describe(s, m);
    }
    describe(value, m);
    if (error == null) {
      m.outcome(Outcome.SUCCESS);
    } else {
      FailureClassifier.Classification c = metrics.classify(error);
      m.outcome(c.outcome()).reason(c.reason());
    }
    if (duration) {
      m.duration(MeteredInterceptor.since(start));
    }
    metrics.record(m);
  }

  private static void cancelled(
      EventMetrics metrics, String event, @Nullable String category, boolean duration, long start) {
    MetricEvent m = MetricEvent.of(event).category(category).outcome(Outcome.CANCELLED);
    if (duration) {
      m.duration(MeteredInterceptor.since(start));
    }
    metrics.record(m);
  }

  private static void describe(@Nullable Object o, MetricEvent m) {
    if (o instanceof MetricSource s) {
      try {
        s.describe(m);
      } catch (RuntimeException ignored) {
        // metrics never break the operation
      }
    }
  }
}
