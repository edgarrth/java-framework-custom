package pe.axiz.spring.audit;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.NoEffect;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Advice reactivo diferido: un registro por suscripción, que espera la entrega antes de completar.
 * Los errores al entregar un éxito se propagan para que la auditoría transaccional no pueda perder
 * evidencia en silencio; la entrega de un fallo o una cancelación nunca reemplaza el resultado de
 * negocio original.
 */
public final class ReactiveAuditableInterceptor implements MethodInterceptor {
  static final Object STATE_KEY = ReactiveAuditableInterceptor.class;
  private final Supplier<ReactiveAuditTrail> trail;
  private final Supplier<AuditContextResolver> context;

  public ReactiveAuditableInterceptor(
      Supplier<ReactiveAuditTrail> trail, Supplier<AuditContextResolver> context) {
    this.trail = Objects.requireNonNull(trail);
    this.context = Objects.requireNonNull(context);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Method method = invocation.getMethod();
    Auditable rule = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
    if (rule == null) return invocation.proceed();
    if (!Mono.class.isAssignableFrom(method.getReturnType())
        && !Flux.class.isAssignableFrom(method.getReturnType()))
      throw new IllegalStateException(
          "@Auditable in a reactive application requires Mono or Flux: " + method.getName());
    if (Flux.class.isAssignableFrom(method.getReturnType())) {
      return Flux.deferContextual(
          ctx -> {
            State state = state(invocation, rule, ctx);
            return ReactivePendingAuditFailures.boundary(
                ReactiveAudit.around(
                    Flux.defer(() -> Flux.from(proceed(invocation)))
                        .contextWrite(values -> values.put(STATE_KEY, state)),
                    value -> success(state, rule),
                    error -> failure(state, rule, error)));
          });
    }
    return Mono.deferContextual(
        ctx -> {
          State state = state(invocation, rule, ctx);
          return ReactivePendingAuditFailures.boundary(
              ReactiveAudit.around(
                  Mono.defer(() -> Mono.from(proceed(invocation)))
                      .contextWrite(values -> values.put(STATE_KEY, state)),
                  value -> {
                    state.result = value;
                    return success(state, rule);
                  },
                  error -> failure(state, rule, error)));
        });
  }

  private State state(MethodInvocation invocation, Auditable rule, ContextView ctx) {
    var record =
        trail
            .get()
            .record(rule.action(), rule.resource())
            .context(ReactiveAuditContext.current(context.get(), ctx, null));
    @Nullable String @Nullable [] names =
        new DefaultParameterNameDiscoverer().getParameterNames(invocation.getMethod());
    Map<String, @Nullable Object> captured = new LinkedHashMap<>();
    @Nullable Object[] args = invocation.getArguments();
    for (int i = 0; i < args.length; i++) {
      String discovered = names != null && i < names.length ? names[i] : null;
      String name = discovered != null ? discovered : "arg" + i;
      @Nullable Object value = args[i];
      if (name.equals(rule.resourceIdArgument()) && value != null)
        record.resourceId(String.valueOf(value));
      captured.put(name, value);
    }
    if (rule.captureArguments()) record.detail("arguments", captured);
    return new State(record, rule);
  }

  private static Mono<Void> success(State state, Auditable rule) {
    return Mono.defer(
        () -> {
          if (state.success || !rule.policy().applies(AuditEvent.Outcome.SUCCESS))
            return Mono.empty();
          state.record.outcomes(
              ReactiveTransactionOutcome.local(state.transaction, null),
              TransactionOutcome.COMMITTED);
          if (rule.captureResult()) state.record.detail("result", state.result);
          return state.record.success();
        });
  }

  private static Mono<Void> failure(State state, Auditable rule, Throwable original) {
    if (!rule.policy().applies(AuditEvent.Outcome.FAILURE)) return Mono.empty();
    state.record.outcomes(
        ReactiveTransactionOutcome.local(state.transaction, original),
        original instanceof NoEffect ? TransactionOutcome.ROLLED_BACK : TransactionOutcome.UNKNOWN);
    return state.record.failure(
        original instanceof AxizException ax
            ? ax.code().value()
            : original.getClass().getSimpleName());
  }

  @SuppressWarnings("unchecked")
  static Publisher<Object> proceed(MethodInvocation invocation) {
    try {
      MethodInvocation call =
          invocation instanceof ProxyMethodInvocation proxy ? proxy.invocableClone() : invocation;
      return Objects.requireNonNull(
          (Publisher<Object>) call.proceed(), "Reactive @Auditable returned null");
    } catch (Throwable error) {
      return Mono.error(error);
    }
  }

  static final class State {
    final ReactiveAuditTrail.Builder record;
    final Auditable rule;
    volatile JdbcTransactionOutcome transaction = new JdbcTransactionOutcome();
    volatile boolean success;
    volatile @Nullable Object result;

    State(ReactiveAuditTrail.Builder record, Auditable rule) {
      this.record = record;
      this.rule = rule;
    }
  }
}
