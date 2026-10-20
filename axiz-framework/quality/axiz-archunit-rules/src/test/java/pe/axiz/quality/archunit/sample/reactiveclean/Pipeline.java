package pe.axiz.quality.archunit.sample.reactiveclean;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Composes publishers without blocking or hooks. */
public class Pipeline {

  public Mono<Integer> doubled(Mono<Integer> value) {
    return value.map(v -> v * 2);
  }

  public Flux<String> names(Flux<Integer> ids) {
    return ids.map(String::valueOf);
  }
}
