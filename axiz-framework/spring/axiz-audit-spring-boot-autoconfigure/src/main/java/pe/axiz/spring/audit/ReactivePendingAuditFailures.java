package pe.axiz.spring.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Búfer propiedad de la suscripción; las escrituras nativas de fallos se ejecutan después de que
 * termina su transacción externa.
 */
final class ReactivePendingAuditFailures {
  private static final Object KEY = ReactivePendingAuditFailures.class;

  private ReactivePendingAuditFailures() {}

  static @Nullable Buffer current(ContextView context) {
    return context.getOrDefault(KEY, null);
  }

  static <T> Mono<T> boundary(Mono<T> work) {
    return Mono.deferContextual(
        context -> {
          if (current(context) != null) return work;
          return Mono.usingWhen(
              Mono.just(new Buffer()),
              buffer -> work.contextWrite(values -> values.put(KEY, buffer)),
              Buffer::drain,
              (buffer, error) -> buffer.drain(),
              Buffer::drain);
        });
  }

  static <T> Flux<T> boundary(Flux<T> work) {
    return Flux.deferContextual(
        context -> {
          if (current(context) != null) return work;
          return Flux.usingWhen(
              Mono.just(new Buffer()),
              buffer -> work.contextWrite(values -> values.put(KEY, buffer)),
              Buffer::drain,
              (buffer, error) -> buffer.drain(),
              Buffer::drain);
        });
  }

  static final class Buffer {
    private final List<Supplier<Mono<Void>>> work = new ArrayList<>();

    synchronized boolean add(Supplier<Mono<Void>> failure) {
      if (work.size() >= 1024) return false;
      work.add(failure);
      return true;
    }

    Mono<Void> drain() {
      return Mono.defer(
          () -> {
            List<Supplier<Mono<Void>>> pending;
            synchronized (this) {
              pending = List.copyOf(work);
              work.clear();
            }
            return Flux.fromIterable(pending).concatMap(Mono::defer).then();
          });
    }
  }
}
