package pe.axiz.spring.logging;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reactor support, loaded only when Reactor is present. */
final class ReactiveLogging {

  private ReactiveLogging() {}

  static boolean isReactive(@Nullable Object result) {
    return result instanceof Mono<?> || result instanceof Flux<?>;
  }

  static Object decorate(
      Object result, Consumer<@Nullable Object> success, Consumer<Throwable> failure) {
    if (result instanceof Mono<?> mono) {
      return mono.doOnSuccess(success::accept).doOnError(failure);
    }
    Flux<?> flux = (Flux<?>) result;
    return flux.doOnComplete(() -> success.accept(null)).doOnError(failure);
  }
}
