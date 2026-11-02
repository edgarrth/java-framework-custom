package pe.axiz.spring.cache;

import java.util.List;
import java.util.Optional;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.cache.CacheScope;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/** Reactor support for {@code @AxizCacheable}; cache I/O uses native asynchronous ports. */
final class ReactiveCaching {

  private ReactiveCaching() {}

  static @Nullable String kind(Class<?> returnType) {
    if (Mono.class.isAssignableFrom(returnType)) {
      return "mono";
    }
    return Flux.class.isAssignableFrom(returnType) ? "flux" : null;
  }

  /**
   * Caches a {@code Mono} or {@code Flux}. The scope (tenant, caller) is read when the publisher is
   * subscribed, from the Reactor context; without a resolvable scope the publisher runs uncached.
   */
  @SuppressWarnings("unchecked")
  static Object wrap(
      AxizCacheableInterceptor in,
      AxizCacheableInterceptor.Plan plan,
      String arguments,
      MethodInvocation invocation)
      throws Throwable {
    // a reactive method that returns null (a contract violation) behaves as an empty publisher

    Mono<Optional<String>> keys =
        scope(plan.scope(), in.callers())
            .map(s -> s.map(p -> AxizCacheableInterceptor.key(plan, p, arguments)));
    if ("mono".equals(plan.reactive())) {
      Mono<Object> source = Mono.defer(() -> Mono.from(proceed(invocation)));
      return keys.flatMap(key -> key.isEmpty() ? source : cachedMono(in, plan, key.get(), source));
    }
    Flux<Object> source = Flux.defer(() -> Flux.from(proceed(invocation)));
    return keys.flatMapMany(
        key -> key.isEmpty() ? source : cachedFlux(in, plan, key.get(), source));
  }

  private static Mono<Object> cachedMono(
      AxizCacheableInterceptor in,
      AxizCacheableInterceptor.Plan plan,
      String key,
      Mono<Object> source) {
    return read(in, plan, key)
        .flatMap(
            hit ->
                hit.isPresent()
                    ? Mono.just(hit.get())
                    : source.flatMap(v -> write(in, plan, key, v).thenReturn(v)));
  }

  @SuppressWarnings("unchecked")
  private static Flux<Object> cachedFlux(
      AxizCacheableInterceptor in,
      AxizCacheableInterceptor.Plan plan,
      String key,
      Flux<Object> source) {
    return read(in, plan, key)
        .flatMapMany(
            hit ->
                hit.isPresent()
                    ? Flux.fromIterable((List<Object>) hit.get())
                    : source.transformDeferred(
                        values -> {
                          List<Object> captured = new java.util.ArrayList<>();
                          java.util.concurrent.atomic.AtomicBoolean overflowed =
                              new java.util.concurrent.atomic.AtomicBoolean();
                          return values
                              .doOnNext(
                                  value -> {
                                    if (overflowed.get()) return;
                                    if (captured.size() < 1000) captured.add(value);
                                    else {
                                      captured.clear();
                                      overflowed.set(true);
                                    }
                                  })
                              .concatWith(
                                  Mono.defer(
                                      () ->
                                          overflowed.get()
                                              ? Mono.empty()
                                              : write(in, plan, key, captured).then(Mono.empty())));
                        }));
  }

  @SuppressWarnings("unchecked")
  private static org.reactivestreams.Publisher<Object> proceed(MethodInvocation invocation) {
    try {
      Object result = invocation.proceed();
      return result == null ? Mono.empty() : (org.reactivestreams.Publisher<Object>) result;
    } catch (Throwable failure) {
      throw reactor.core.Exceptions.propagate(failure);
    }
  }

  private static Mono<Optional<Object>> read(
      AxizCacheableInterceptor in, AxizCacheableInterceptor.Plan plan, String key) {
    String full = in.asyncCaches().prefix() + ":" + key;
    return ReactiveStages.from(() -> in.asyncCaches().store().get(full))
        .flatMap(
            hit -> {
              if (hit.isEmpty()) return Mono.just(Optional.empty());
              try {
                return Mono.just(
                    Optional.ofNullable(in.json().readValue(hit.get(), plan.valueType())));
              } catch (RuntimeException failure) {
                return ReactiveStages.from(() -> in.asyncCaches().store().evict(full))
                    .thenReturn(Optional.empty());
              }
            });
  }

  private static Mono<Void> write(
      AxizCacheableInterceptor in, AxizCacheableInterceptor.Plan plan, String key, Object value) {
    return Mono.defer(
        () ->
            ReactiveStages.from(
                () ->
                    in.asyncCaches()
                        .store()
                        .put(
                            in.asyncCaches().prefix() + ":" + key,
                            in.json().writeValueAsBytes(value),
                            plan.ttl())));
  }

  /** Scope part of the key, from the Reactor context of the subscriber. */
  static Mono<Optional<String>> scope(CacheScope scope, @Nullable CacheCallerResolver callers) {
    return Mono.deferContextual(
        ctx -> {
          String tenant = scope == CacheScope.GLOBAL ? null : tenant(ctx);
          if (scope != CacheScope.CALLER) {
            return Mono.just(Optional.of(CacheScopes.part(scope, tenant, null)));
          }
          if (!CacheScopes.REACTIVE_SECURITY || callers == null) {
            return Mono.just(Optional.<String>empty());
          }
          return SecurityCallers.reactive(callers)
              .map(caller -> Optional.of(CacheScopes.part(scope, tenant, caller)))
              .defaultIfEmpty(Optional.empty());
        });
  }

  private static @Nullable String tenant(ContextView ctx) {
    Object fromContext = ctx.getOrDefault(CacheScopes.TENANT_KEY, null);
    return fromContext instanceof String s ? s : CacheScopes.threadTenant();
  }
}
