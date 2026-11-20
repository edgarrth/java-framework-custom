package pe.axiz.spring.architecturefixture;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Deliberate architecture violation used only by the negative regression test. */
public class OffloadingClient {
  public Mono<String> call() {
    return Mono.fromCallable(() -> "blocking-provider").subscribeOn(Schedulers.boundedElastic());
  }
}
