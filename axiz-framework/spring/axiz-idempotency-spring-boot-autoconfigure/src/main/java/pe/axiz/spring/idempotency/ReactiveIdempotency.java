package pe.axiz.spring.idempotency;

import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NoEffect;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyErrors;
import pe.axiz.core.idempotency.IdempotencyLease;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Mitad reactiva de {@link IdempotencyMethodInterceptor}; solo se carga cuando Reactor está
 * presente.
 */
final class ReactiveIdempotency {

  /**
   * Clave en el contexto de Reactor del estado de la solicitud establecido por {@link
   * IdempotencyWebFilter}.
   */
  static final String CONTEXT_KEY = "axiz.idempotency.exchange";

  private ReactiveIdempotency() {}

  static boolean handles(Class<?> returnType) {
    return Publisher.class.isAssignableFrom(returnType);
  }

  static Object apply(
      MethodInvocation invocation,
      Idempotent rule,
      String operation,
      Supplier<AsyncIdempotencyService> service,
      Supplier<AsyncPayloadProtector> protection) {
    Mono<Void> gate =
        Mono.deferContextual(
            ctx -> {
              IdempotencyExchange exchange = ctx.getOrDefault(CONTEXT_KEY, null);
              if (exchange == null || exchange.execution() != null) {
                return Mono.<Void>empty(); // método seguro, o ya decidido
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
              var binding = IdempotencyPayloads.Binding.of(operation, scope, key);
              exchange.binding = binding;
              return ReactiveStages.from(() -> service.get().begin(scope, key, fingerprint))
                  .flatMap(
                      decision -> {
                        if (decision instanceof IdempotencyService.Replay replay) {
                          return ReactiveStages.from(
                                  () ->
                                      IdempotencyPayloads.reveal(
                                          replay.response(), binding, protection.get()))
                              .flatMap(
                                  revealed -> Mono.<Void>error(new IdempotentReplay(revealed)));
                        }
                        exchange.execution((IdempotencyService.Proceed) decision);
                        exchange.lease =
                            service.get().startLease((IdempotencyService.Proceed) decision);
                        return Mono.<Void>empty();
                      });
            });
    if (Flux.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
      return gate.thenMany(Flux.defer(() -> Flux.from(proceed(invocation))))
          .onErrorResume(ReactiveIdempotency::recordFailure);
    }
    return gate.then(Mono.defer(() -> Mono.from(proceed(invocation))))
        .onErrorResume(ReactiveIdempotency::recordFailure);
  }

  private static <T> Mono<T> recordFailure(Throwable failure) {
    return Mono.deferContextual(
        context -> {
          IdempotencyExchange state = context.getOrDefault(CONTEXT_KEY, null);
          if (state != null && failure instanceof NoEffect) state.noEffect = true;
          return Mono.error(failure);
        });
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
   * Estado por solicitud compartido por el web filter (que calcula la huella de la solicitud y
   * almacena la respuesta) y el advice (que toma la decisión después de la autorización).
   */
  static final class IdempotencyExchange {
    private final @Nullable String key;
    private final @Nullable String fingerprint;
    private volatile IdempotencyService.@Nullable Proceed execution;
    private volatile @Nullable IdempotencyLease lease;
    volatile boolean noEffect;
    volatile IdempotencyPayloads.@Nullable Binding binding;

    void closeLease() {
      IdempotencyLease current = lease;
      if (current != null) current.close();
    }

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
