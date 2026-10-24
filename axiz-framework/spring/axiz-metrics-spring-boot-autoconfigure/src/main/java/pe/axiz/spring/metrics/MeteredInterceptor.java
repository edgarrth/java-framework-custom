package pe.axiz.spring.metrics;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.Metered;

/**
 * Applies {@link Metered}: counts the call, times it and derives outcome and reason from the
 * failure. Arguments and results implementing {@code MetricSource} contribute amount, value and
 * tags. Reactive results are measured when they complete.
 */
public class MeteredInterceptor implements MethodInterceptor {

  private static final boolean REACTOR = ClassUtils.isPresent("reactor.core.publisher.Mono", null);

  private final SingletonSupplier<EventMetrics> metrics;

  public MeteredInterceptor(Supplier<EventMetrics> metrics) {
    this.metrics = SingletonSupplier.of(metrics);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Method method = invocation.getMethod();
    Metered metered = AnnotatedElementUtils.findMergedAnnotation(method, Metered.class);
    if (metered == null) {
      return invocation.proceed();
    }
    EventMetrics m = metrics.obtain();
    String category = metered.category().isBlank() ? null : metered.category();
    List<?> sources = Arrays.asList(invocation.getArguments());
    if (REACTOR && ReactiveMetering.returnsReactive(method)) {
      return ReactiveMetering.measure(
          m, metered.event(), category, metered.duration(), sources, invocation);
    }
    return m.<@Nullable Object>observe(
        metered.event(), category, metered.duration(), sources, invocation::proceed);
  }

  static Duration since(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
