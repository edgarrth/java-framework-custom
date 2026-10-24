package pe.axiz.spring.cache;

import java.util.List;
import java.util.Optional;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Reactor support for {@code @AxizCacheable}; cache I/O runs on a worker scheduler. */
final class ReactiveCaching {

  private ReactiveCaching() {}

  static @Nullable String kind(Class<?> returnType) {
    if (Mono.class.isAssignableFrom(returnType)) {
      return "mono";
    }
    return Flux.class.isAssignableFrom(returnType) ? "flux" : null;
  }

  @SuppressWarnings("unchecked")
  static Object wrap(
      AxizCacheableInterceptor in,
      AxizCacheableInterceptor.Plan plan,
      String key,
      MethodInvocation invocation)
      throws Throwable {
    // a reactive method that returns null (a contract violation) behaves as an empty publisher
    Object result = invocation.proceed();
    Mono<Optional<Object>> cached =
        Mono.fromCallable(() -> in.read(plan, key, plan.valueType()))
            .subscribeOn(Schedulers.boundedElastic());
    if ("mono".equals(plan.reactive())) {
      Mono<Object> source = result == null ? Mono.empty() : (Mono<Object>) result;
      return cached.flatMap(
          hit ->
              hit.isPresent()
                  ? Mono.just(hit.get())
                  : source.flatMap(
                      v ->
                          Mono.fromRunnable(() -> in.write(plan, key, v))
                              .subscribeOn(Schedulers.boundedElastic())
                              .thenReturn(v)));
    }
    Flux<Object> source = result == null ? Flux.empty() : (Flux<Object>) result;
    return cached.flatMapMany(
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
}
