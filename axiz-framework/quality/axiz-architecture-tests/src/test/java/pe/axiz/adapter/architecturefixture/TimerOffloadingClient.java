package pe.axiz.adapter.architecturefixture;

import java.time.Duration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public class TimerOffloadingClient {
  public Mono<String> call(Scheduler scheduler) {
    return Mono.just("value").delayElement(Duration.ofMillis(1), scheduler);
  }
}
