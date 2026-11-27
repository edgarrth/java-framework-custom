package pe.axiz.kit.architecturefixture;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public class InjectedOffloadingClient {
  public Mono<String> call(Scheduler scheduler) {
    return Mono.fromCallable(() -> "blocking-provider").subscribeOn(scheduler);
  }
}
