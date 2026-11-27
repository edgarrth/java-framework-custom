package pe.axiz.quality.archunit.sample.reactive;

import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Bloquea pipelines reactivos: lo que informa NO_BLOCK_IN_REACTIVE_CODE. */
public class BlockingAdapter {

  public CompletableFuture<String> offload() {
    return Mono.fromCallable(() -> CompletableFuture.completedFuture("bad").join())
        .subscribeOn(Schedulers.boundedElastic())
        .toFuture();
  }

  public Integer one() {
    return Mono.just(1).block();
  }

  public Integer last() {
    return Flux.just(1, 2).blockLast();
  }
}
