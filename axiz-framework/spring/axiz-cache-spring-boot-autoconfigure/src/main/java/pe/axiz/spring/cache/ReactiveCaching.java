package pe.axiz.spring.cache;

import java.util.List;
import java.util.Optional;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.cache.CacheScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/** Reactor support for {@code @AxizCacheable}; cache I/O runs on a worker scheduler. */
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
    Object result = invocation.proceed();
    Mono<Optional<String>> keys =
        scope(plan.scope(), in.callers())
            .map(s -> s.map(p -> AxizCacheableInterceptor.key(plan, p, arguments)));
    if ("mono".equals(plan.reactive())) {
      Mono<Object> source = result == null ? Mono.empty() : (Mono<Object>) result;
      return keys.flatMap(key -> key.isEmpty() ? source : cachedMono(in, plan, key.get(), source));
    }
    Flux<Object> source = result == null ? Flux.empty() : (Flux<Object>) result;
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
                    : source.flatMap(
                        v ->
                            Mono.fromRunnable(() -> in.write(plan, key, v))
                                .subscribeOn(Schedulers.boundedElastic())
                                .thenReturn(v)));
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
                    : source
                        .collectList()
                        .flatMapMany(
                            list ->
                                Mono.fromRunnable(() -> in.write(plan, key, list))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .thenMany(Flux.fromIterable(list))));
  }

  private static Mono<Optional<Object>> read(
      AxizCacheableInterceptor in, AxizCacheableInterceptor.Plan plan, String key) {
    return Mono.fromCallable(() -> in.read(plan, key, plan.valueType()))
        .subscribeOn(Schedulers.boundedElastic());
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
