package pe.axiz.adapter.architecturefixture;

import reactor.core.publisher.Flux;
import reactor.core.publisher.ParallelFlux;
import reactor.core.scheduler.Scheduler;

public class ParallelOffloadingClient {
  public ParallelFlux<String> call(Scheduler scheduler) {
    return Flux.just("value").parallel().runOn(scheduler);
  }
}
