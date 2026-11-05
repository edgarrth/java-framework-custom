package pe.axiz.spring.audit;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.NoEffect;

/**
 * Applies {@link Auditable}: records who did what on which resource and with what result. Details
 * are sanitized for the audit channel by {@link AuditTrail}; the business call is never affected by
 * a publishing failure.
 */
public class AuditableInterceptor implements MethodInterceptor {

  private static final ThreadLocal<@Nullable InvocationState> CURRENT = new ThreadLocal<>();

  static @Nullable InvocationState current() {
    return CURRENT.get();
  }

  static final class InvocationState {
    final Auditable rule;
    final AuditTrail.Builder record;
    JdbcTransactionOutcome transaction = JdbcTransactionOutcome.capture();
    boolean success;

    InvocationState(Auditable rule, AuditTrail.Builder record) {
      this.rule = rule;
      this.record = record;
    }
  }

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
    InvocationState previous = CURRENT.get();
    InvocationState state = new InvocationState(a, record);
    CURRENT.set(state);
    try (var boundary = PendingAuditFailures.open()) {
      Object result;
      try {
        result = invocation.proceed();
      } catch (Throwable failure) {
        record.outcomes(
            state.transaction.local(failure),
            failure instanceof NoEffect
                ? TransactionOutcome.ROLLED_BACK
                : TransactionOutcome.UNKNOWN);
        finish(a, record, null, failure);
        throw failure;
      }
      if (REACTOR && result != null && ReactiveAudit.isReactive(result)) {
        return ReactiveAudit.decorate(
            result,
            value -> {
              record.outcomes(state.transaction.local(null), TransactionOutcome.COMMITTED);
              finish(a, record, value, null);
            },
            failure -> {
              record.outcomes(state.transaction.local(failure), TransactionOutcome.UNKNOWN);
              finish(a, record, null, failure);
            });
      }
      if (!state.success) {
        record.outcomes(state.transaction.local(null), TransactionOutcome.COMMITTED);
        finish(a, record, result, null);
      }
      return result;
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
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
      LoggerFactory.getLogger(AuditableInterceptor.class)
          .warn("Audit event could not be recorded", publishFailure);
    }
  }
}
