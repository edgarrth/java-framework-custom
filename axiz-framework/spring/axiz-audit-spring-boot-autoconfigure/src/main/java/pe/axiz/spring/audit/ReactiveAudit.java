package pe.axiz.spring.audit;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
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
      return mono.doOnSuccess(success::accept).doOnError(failure);
    }
    return ((Flux<?>) result).doOnComplete(() -> success.accept(null)).doOnError(failure);
  }
}
