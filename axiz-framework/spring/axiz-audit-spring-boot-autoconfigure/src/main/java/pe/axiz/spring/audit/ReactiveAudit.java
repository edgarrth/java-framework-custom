package pe.axiz.spring.audit;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reactor support for {@code @Auditable}; loaded only when Reactor is present. */
final class ReactiveAudit {

  private ReactiveAudit() {}

  static boolean isReactive(@Nullable Object o) {
    return o instanceof Mono<?> || o instanceof Flux<?>;
  }

  static Object decorate(
      Object result, Consumer<@Nullable Object> success, Consumer<Throwable> failure) {
    if (result instanceof Mono<?> mono) {
      return mono.doOnSuccess(success).doOnError(failure);
    }
    return ((Flux<?>) result).doOnComplete(() -> success.accept(null)).doOnError(failure);
  }

  static <T> Mono<T> around(
      Mono<T> work,
      Function<@Nullable T, Mono<Void>> success,
      Function<Throwable, Mono<Void>> failure) {
    return Mono.defer(
        () -> {
          var terminal = new AtomicBoolean();
          Mono<T> tracked =
              work.materialize()
                  .flatMap(
                      signal -> {
                        terminal.set(true);
                        Throwable error = signal.getThrowable();
                        if (error != null) return failed(failure, error).then(Mono.error(error));
                        @Nullable T value = signal.get();
                        Mono<Void> delivery = Mono.defer(() -> success.apply(value));
                        return value == null
                            ? delivery.then(Mono.empty())
                            : delivery.thenReturn(value);
                      });
          return Mono.usingWhen(
              Mono.just(terminal),
              ignored -> tracked,
              ignored -> Mono.empty(),
              (ignored, error) -> Mono.empty(),
              state ->
                  state.compareAndSet(false, true)
                      ? failed(failure, new CancellationException("cancelled"))
                      : Mono.empty());
        });
  }

  static <T> Flux<T> around(
      Flux<T> work,
      Function<@Nullable T, Mono<Void>> success,
      Function<Throwable, Mono<Void>> failure) {
    return Flux.defer(
        () -> {
          var terminal = new AtomicBoolean();
          Flux<T> tracked =
              work.onErrorResume(
                      error -> {
                        terminal.set(true);
                        return failed(failure, error).thenMany(Flux.error(error));
                      })
                  .concatWith(
                      Mono.defer(
                              () -> {
                                terminal.set(true);
                                return success.apply(null);
                              })
                          .thenMany(Flux.empty()));
          return Flux.usingWhen(
              Mono.just(terminal),
              ignored -> tracked,
              ignored -> Mono.empty(),
              (ignored, error) -> Mono.empty(),
              state ->
                  state.compareAndSet(false, true)
                      ? failed(failure, new CancellationException("cancelled"))
                      : Mono.empty());
        });
  }

  private static Mono<Void> failed(Function<Throwable, Mono<Void>> failure, Throwable original) {
    return Mono.defer(() -> failure.apply(original))
        .onErrorResume(
            delivery -> {
              if (delivery != original) original.addSuppressed(delivery);
              LoggerFactory.getLogger(ReactiveAudit.class)
                  .warn("Audit failure delivery was rejected", delivery);
              return Mono.empty();
            });
  }
}
