package pe.axiz.spring.idempotency;

import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.idempotency.IdempotencyErrors;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reactive half of {@link IdempotencyMethodInterceptor}; loaded only when Reactor is present. */
final class ReactiveIdempotency {

  /** Reactor context key of the request state set by {@link IdempotencyWebFilter}. */
  static final String CONTEXT_KEY = "axiz.idempotency.exchange";

  private ReactiveIdempotency() {}

  static boolean handles(Class<?> returnType) {
    return Publisher.class.isAssignableFrom(returnType);
  }

  static Object apply(
      MethodInvocation invocation,
      Idempotent rule,
      String operation,
      Supplier<pe.axiz.core.idempotency.AsyncIdempotencyService> service) {
    Mono<Void> gate =
        Mono.deferContextual(
            ctx -> {
              IdempotencyExchange exchange = ctx.getOrDefault(CONTEXT_KEY, null);
              if (exchange == null || exchange.execution() != null) {
                return Mono.<Void>empty(); // safe method, or already decided
              }
              String key = exchange.key();
              String fingerprint = exchange.fingerprint();
              if (key == null || fingerprint == null) {
                return rule.required()
                    ? Mono.<Void>error(
                        new BusinessException(
                            IdempotencyErrors.KEY_REQUIRED, "Idempotency-Key missing"))
                    : Mono.<Void>empty();
              }
              RequestMetadata caller = ctx.getOrDefault(RequestContext.KEY, null);
              String scope = IdempotencySupport.scope(operation, caller);
              return ReactiveStages.from(() -> service.get().begin(scope, key, fingerprint))
                  .flatMap(
                      decision -> {
                        if (decision instanceof IdempotencyService.Replay replay) {
                          return Mono.<Void>error(new IdempotentReplay(replay.response()));
                        }
                        exchange.execution((IdempotencyService.Proceed) decision);
                        return Mono.<Void>empty();
                      });
            });
    if (Flux.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
      return gate.thenMany(Flux.defer(() -> Flux.from(proceed(invocation))));
    }
    return gate.then(Mono.defer(() -> Mono.from(proceed(invocation))));
  }

  @SuppressWarnings("unchecked")
  private static Publisher<Object> proceed(MethodInvocation invocation) {
    try {
      Object result = invocation.proceed();
      return result == null ? Mono.empty() : (Publisher<Object>) result;
    } catch (Throwable e) {
      throw Exceptions.propagate(e);
    }
  }

  /**
   * Per-request state shared by the web filter (which fingerprints the request and stores the
   * response) and the advice (which takes the decision after authorization).
   */
  static final class IdempotencyExchange {
    private final @Nullable String key;
    private final @Nullable String fingerprint;
    private volatile IdempotencyService.@Nullable Proceed execution;

    IdempotencyExchange(@Nullable String key, @Nullable String fingerprint) {
      this.key = key;
      this.fingerprint = fingerprint;
    }

    @Nullable String key() {
      return key;
    }

    @Nullable String fingerprint() {
      return fingerprint;
    }

    IdempotencyService.@Nullable Proceed execution() {
      return execution;
    }

    void execution(IdempotencyService.Proceed value) {
      this.execution = Objects.requireNonNull(value);
    }
  }
}
