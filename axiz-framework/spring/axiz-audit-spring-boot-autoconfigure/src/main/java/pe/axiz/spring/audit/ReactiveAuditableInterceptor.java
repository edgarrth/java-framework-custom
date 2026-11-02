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
import pe.axiz.core.errors.AxizException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Deferred reactive advice: one record per subscription, awaiting delivery before completion.
 * Success delivery errors propagate so transactional audit cannot silently lose evidence;
 * failure/cancellation delivery never replaces the original business outcome.
 */
public final class ReactiveAuditableInterceptor implements MethodInterceptor {
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
            return ReactiveAudit.around(
                Flux.defer(() -> Flux.from(proceed(invocation))),
                value -> success(state, rule),
                error -> failure(state, rule, error));
          });
    }
    return Mono.deferContextual(
        ctx -> {
          State state = state(invocation, rule, ctx);
          return ReactiveAudit.around(
              Mono.defer(() -> Mono.from(proceed(invocation))),
              value -> {
                state.result = value;
                return success(state, rule);
              },
              error -> failure(state, rule, error));
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
    return new State(record);
  }

  private static Mono<Void> success(State state, Auditable rule) {
    return Mono.defer(
        () -> {
          if (!rule.policy().applies(AuditEvent.Outcome.SUCCESS)) return Mono.empty();
          if (rule.captureResult()) state.record.detail("result", state.result);
          return state.record.success();
        });
  }

  private static Mono<Void> failure(State state, Auditable rule, Throwable original) {
    if (!rule.policy().applies(AuditEvent.Outcome.FAILURE)) return Mono.empty();
    return state.record.failure(
        original instanceof AxizException ax
            ? ax.code().value()
            : original.getClass().getSimpleName());
  }

  @SuppressWarnings("unchecked")
  private static Publisher<Object> proceed(MethodInvocation invocation) {
    try {
      MethodInvocation call =
          invocation instanceof ProxyMethodInvocation proxy ? proxy.invocableClone() : invocation;
      return Objects.requireNonNull(
          (Publisher<Object>) call.proceed(), "Reactive @Auditable returned null");
    } catch (Throwable error) {
      return Mono.error(error);
    }
  }

  private static final class State {
    final ReactiveAuditTrail.Builder record;
    @Nullable Object result;

    State(ReactiveAuditTrail.Builder record) {
      this.record = record;
    }
  }
}
