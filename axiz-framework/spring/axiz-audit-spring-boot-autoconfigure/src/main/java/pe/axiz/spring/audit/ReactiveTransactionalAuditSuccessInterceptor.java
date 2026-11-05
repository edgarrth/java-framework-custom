package pe.axiz.spring.audit;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.commons.TransactionOutcome;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Success stays inside the business transaction while failure is handled by the outer advice. */
final class ReactiveTransactionalAuditSuccessInterceptor implements MethodInterceptor {
  private final BooleanSupplier durable;

  ReactiveTransactionalAuditSuccessInterceptor(BooleanSupplier durable) {
    this.durable = durable;
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) {
    if (Flux.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
      return Flux.deferContextual(
          context -> {
            var state =
                Objects.requireNonNull(
                    context.<ReactiveAuditableInterceptor.State>getOrDefault(
                        ReactiveAuditableInterceptor.STATE_KEY, null),
                    "Missing audit invocation context");
            return ReactiveTransactionOutcome.capture()
                .flatMapMany(
                    trace -> {
                      state.transaction = trace;
                      return ReactiveAudit.around(
                          Flux.defer(
                              () -> Flux.from(ReactiveAuditableInterceptor.proceed(invocation))),
                          value -> success(state),
                          error -> Mono.empty());
                    });
          });
    }
    return Mono.deferContextual(
        context -> {
          var state =
              Objects.requireNonNull(
                  context.<ReactiveAuditableInterceptor.State>getOrDefault(
                      ReactiveAuditableInterceptor.STATE_KEY, null),
                  "Missing audit invocation context");
          return ReactiveTransactionOutcome.capture()
              .flatMap(
                  trace -> {
                    state.transaction = trace;
                    return ReactiveAudit.around(
                        Mono.defer(
                            () -> Mono.from(ReactiveAuditableInterceptor.proceed(invocation))),
                        value -> {
                          state.result = value;
                          return success(state);
                        },
                        error -> Mono.empty());
                  });
        });
  }

  private Mono<Void> success(ReactiveAuditableInterceptor.State state) {
    if (!durable.getAsBoolean() || !state.rule.policy().applies(AuditEvent.Outcome.SUCCESS))
      return Mono.empty();
    return TransactionSynchronizationManager.forCurrentTransaction()
        .map(TransactionSynchronizationManager::isActualTransactionActive)
        .onErrorResume(NoTransactionException.class, ignored -> Mono.just(false))
        .flatMap(
            active -> {
              if (!active) return Mono.empty();
              if (state.rule.captureResult()) state.record.detail("result", state.result);
              return state
                  .record
                  .outcomes(TransactionOutcome.COMMITTED, TransactionOutcome.COMMITTED)
                  .success()
                  .doOnSuccess(ignored -> state.success = true);
            });
  }
}
