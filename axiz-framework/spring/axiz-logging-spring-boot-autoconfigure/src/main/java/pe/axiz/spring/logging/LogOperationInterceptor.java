package pe.axiz.spring.logging;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import pe.axiz.core.logging.LogFields;
import pe.axiz.core.logging.LogOperation;
import pe.axiz.core.logging.NoLog;
import pe.axiz.core.logging.OperationLogger;
import pe.axiz.core.logging.ScopedMdc;

/**
 * Applies {@link LogOperation}: entry and exit lines with duration and outcome. Reactive return
 * types are logged when they complete, not when the method returns.
 */
public final class LogOperationInterceptor implements MethodInterceptor {

  private static final boolean REACTOR = ClassUtils.isPresent("reactor.core.publisher.Mono", null);

  private final OperationLogger logger;
  private final FailureKind failures;
  private final ParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();
  private final Map<Method, Descriptor> cache = new ConcurrentHashMap<>();

  public LogOperationInterceptor(OperationLogger logger, FailureKind failures) {
    this.logger = Objects.requireNonNull(logger);
    this.failures = Objects.requireNonNull(failures);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Class<?> target =
        invocation.getThis() != null
            ? AopUtils.getTargetClass(invocation.getThis())
            : invocation.getMethod().getDeclaringClass();
    Descriptor d = cache.computeIfAbsent(invocation.getMethod(), m -> describe(m, target));
    long start = System.nanoTime();
    try (ScopedMdc ignored = ScopedMdc.of(LogFields.OPERATION, d.operation())) {
      if (d.annotation().logArguments()) {
        logger.entry(
            d.operation(),
            d.argumentNames(),
            invocation.getArguments(),
            d.excluded(),
            d.annotation().level());
      } else {
        logger.entry(d.operation(), d.annotation().level());
      }
      Object result;
      try {
        result = invocation.proceed();
      } catch (Throwable t) {
        fail(d, start, t);
        throw t;
      }
      if (REACTOR && result != null && ReactiveLogging.isReactive(result)) {
        return ReactiveLogging.decorate(
            result, value -> succeed(d, start, value), error -> fail(d, start, error));
      }
      succeed(d, start, result);
      return result;
    }
  }

  private void succeed(Descriptor d, long start, @Nullable Object result) {
    logger.success(
        d.operation(), elapsed(start), result, d.annotation().logResult(), d.annotation().level());
  }

  private void fail(Descriptor d, long start, Throwable error) {
    logger.failure(
        d.operation(), elapsed(start), error, failures.isExpected(error), failures.code(error));
  }

  private static Duration elapsed(long start) {
    return Duration.ofNanos(System.nanoTime() - start);
  }

  private Descriptor describe(Method method, Class<?> target) {
    LogOperation annotation =
        AnnotatedElementUtils.findMergedAnnotation(method, LogOperation.class);
    if (annotation == null) {
      annotation = AnnotatedElementUtils.findMergedAnnotation(target, LogOperation.class);
    }
    Objects.requireNonNull(annotation, "@LogOperation not found");
    String operation =
        annotation.value().isBlank()
            ? target.getSimpleName() + "." + method.getName()
            : annotation.value();
    @Nullable String @Nullable [] discovered = names.getParameterNames(method);
    List<String> argNames = new ArrayList<>();
    for (int i = 0; i < method.getParameterCount(); i++) {
      String n = discovered != null && i < discovered.length ? discovered[i] : null;
      argNames.add(n != null ? n : "arg" + i);
    }
    Annotation[][] params = method.getParameterAnnotations();
    boolean[] excluded = new boolean[params.length];
    for (int i = 0; i < params.length; i++) {
      excluded[i] = Arrays.stream(params[i]).anyMatch(a -> a.annotationType() == NoLog.class);
    }
    return new Descriptor(operation, annotation, argNames, excluded);
  }

  /** Internal holder; a class rather than a record because it holds an array. */
  private static final class Descriptor {
    private final String operation;
    private final LogOperation annotation;
    private final List<String> argumentNames;
    private final boolean[] excluded;

    Descriptor(
        String operation, LogOperation annotation, List<String> argumentNames, boolean[] excluded) {
      this.operation = operation;
      this.annotation = annotation;
      this.argumentNames = argumentNames;
      this.excluded = excluded;
    }

    String operation() {
      return operation;
    }

    LogOperation annotation() {
      return annotation;
    }

    List<String> argumentNames() {
      return argumentNames;
    }

    boolean[] excluded() {
      return excluded;
    }
  }
}
