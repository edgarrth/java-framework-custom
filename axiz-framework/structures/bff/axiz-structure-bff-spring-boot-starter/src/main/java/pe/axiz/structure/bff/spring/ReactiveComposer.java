package pe.axiz.structure.bff.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.structure.bff.Composition;
import reactor.core.publisher.Mono;

/**
 * Composición reactiva para BFF de WebFlux: las partes se suscriben en paralelo, las partes
 * opcionales recurren a su valor alternativo ante error o timeout y nada bloquea.
 */
public final class ReactiveComposer {

  private static final Object NONE = new Object();

  private final Duration timeout;
  private final List<String> degraded = new CopyOnWriteArrayList<>();
  private final Map<String, Mono<Object>> parts = new LinkedHashMap<>();

  private ReactiveComposer(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout);
  }

  public static ReactiveComposer within(Duration timeout) {
    return new ReactiveComposer(timeout);
  }

  @SuppressWarnings("unchecked")
  public <T> ReactiveComposer required(String name, Mono<T> call) {
    parts.put(name, (Mono<Object>) call.timeout(timeout));
    return this;
  }

  @SuppressWarnings("unchecked")
  public <T> ReactiveComposer optional(String name, Mono<T> call, Supplier<@Nullable T> fallback) {
    parts.put(
        name,
        ((Mono<Object>) call.timeout(timeout))
            .onErrorResume(
                e -> {
                  degraded.add(name);
                  Object v = fallback.get();
                  return v == null ? Mono.just(NONE) : Mono.just(v);
                }));
    return this;
  }

  public Mono<Composition> run() {
    List<String> names = new ArrayList<>();
    List<Mono<Object>> monos = new ArrayList<>();
    parts.forEach(
        (name, part) -> {
          names.add(name);
          monos.add(part.defaultIfEmpty(NONE));
        });
    return Mono.zip(
        monos,
        results -> {
          Map<String, @Nullable Object> values = new LinkedHashMap<>();
          for (int i = 0; i < names.size(); i++) {
            values.put(names.get(i), results[i] == NONE ? null : results[i]);
          }
          return new Composition(values, degraded);
        });
  }
}
