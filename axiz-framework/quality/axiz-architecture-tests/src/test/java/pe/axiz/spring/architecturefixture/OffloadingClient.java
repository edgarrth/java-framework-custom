package pe.axiz.spring.architecturefixture;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Infracción deliberada de arquitectura que solo usa la prueba de regresión negativa. */
public class OffloadingClient {
  public Mono<String> call() {
    return Mono.fromCallable(() -> "blocking-provider").subscribeOn(Schedulers.boundedElastic());
  }
}
