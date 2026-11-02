package pe.axiz.quality.archunit.sample.reactive;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Blocks reactive pipelines: what NO_BLOCK_IN_REACTIVE_CODE reports. */
public class BlockingAdapter {

  public java.util.concurrent.CompletableFuture<String> offload() {
    return Mono.fromCallable(
            () -> java.util.concurrent.CompletableFuture.completedFuture("bad").join())
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .toFuture();
  }

  public Integer one() {
    return Mono.just(1).block();
  }

  public Integer last() {
    return Flux.just(1, 2).blockLast();
  }
}
