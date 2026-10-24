package pe.axiz.spring.audit;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.errors.AxizException;

/**
 * Applies {@link Auditable}: records who did what on which resource and with what result. Details
 * are sanitized for the audit channel by {@link AuditTrail}; the business call is never affected by
 * a publishing failure.
 */
public class AuditableInterceptor implements MethodInterceptor {

  private static final boolean REACTOR = ClassUtils.isPresent("reactor.core.publisher.Mono", null);

  private final SingletonSupplier<AuditTrail> trail;
  private final SingletonSupplier<AuditContextResolver> context;
  private final Map<Method, String[]> names = new ConcurrentHashMap<>();
  private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

  public AuditableInterceptor(Supplier<AuditTrail> trail, Supplier<AuditContextResolver> context) {
    this.trail = SingletonSupplier.of(trail);
    this.context = SingletonSupplier.of(context);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Method method = invocation.getMethod();
    Auditable a = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
    if (a == null) {
      return invocation.proceed();
    }
    AuditTrail.Builder record =
        trail.obtain().record(a.action(), a.resource()).context(context.obtain().current(null));
    String[] params = names.computeIfAbsent(method, this::parameterNames);
    @Nullable Object[] args = invocation.getArguments();
    Map<String, @Nullable Object> captured = new LinkedHashMap<>();
    for (int i = 0; i < args.length && i < params.length; i++) {
      if (params[i].equals(a.resourceIdArgument()) && args[i] != null) {
        record.resourceId(String.valueOf(args[i]));
      }
      captured.put(params[i], args[i]);
    }
    if (a.captureArguments()) {
      record.detail("arguments", captured);
    }
    Object result;
    try {
      result = invocation.proceed();
    } catch (Throwable t) {
      finish(a, record, null, t);
      throw t;
    }
    if (REACTOR && result != null && ReactiveAudit.isReactive(result)) {
      return ReactiveAudit.decorate(
          result, v -> finish(a, record, v, null), e -> finish(a, record, null, e));
    }
    finish(a, record, result, null);
    return result;
  }

  /** Parameter names, or {@code argN} where the compiler did not keep them. */
  private String[] parameterNames(Method m) {
    @Nullable String @Nullable [] discovered = discoverer.getParameterNames(m);
    String[] out = new String[m.getParameterCount()];
    for (int i = 0; i < out.length; i++) {
      String n = discovered != null && i < discovered.length ? discovered[i] : null;
      out[i] = n != null ? n : "arg" + i;
    }
    return out;
  }

  static void finish(
      Auditable a, AuditTrail.Builder record, @Nullable Object result, @Nullable Throwable error) {
    try {
      if (error == null) {
        if (a.policy().applies(AuditEvent.Outcome.SUCCESS)) {
          if (a.captureResult()) {
            record.detail("result", result);
          }
          record.success();
        }
      } else if (a.policy().applies(AuditEvent.Outcome.FAILURE)) {
        record.failure(
            error instanceof AxizException ax
                ? ax.code().value()
                : error.getClass().getSimpleName());
      }
    } catch (RuntimeException publishFailure) {
      org.slf4j.LoggerFactory.getLogger(AuditableInterceptor.class)
          .warn("Audit event could not be recorded", publishFailure);
    }
  }
}
