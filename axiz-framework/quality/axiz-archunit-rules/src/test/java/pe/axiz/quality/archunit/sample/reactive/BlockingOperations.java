package pe.axiz.quality.archunit.sample.reactive;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Negative fixtures are imported by ArchUnit, never executed. */
public class BlockingOperations {
  public String join(CompletableFuture<String> result) {
    return result.join();
  }

  public String block(Mono<String> value) {
    return value.block();
  }

  public Integer last(Flux<Integer> values) {
    return values.blockLast();
  }

  public java.util.stream.Stream<Integer> stream(Flux<Integer> values) {
    return values.toStream();
  }

  public Scheduler sharedOffload() {
    return Schedulers.boundedElastic();
  }

  public String waitFuture(Future<String> result) throws Exception {
    return result.get();
  }

  public String waitConcrete(CompletableFuture<String> result) throws Exception {
    return result.get();
  }

  public Integer first(Flux<Integer> values) {
    return values.blockFirst();
  }

  public Iterable<Integer> iterable(Flux<Integer> values) {
    return values.toIterable();
  }

  public Scheduler hiddenOffload() {
    return Schedulers.newBoundedElastic(1, 1, "hidden");
  }

  public void delay() throws Exception {
    Thread.sleep(1);
  }

  public HttpResponse<String> request(HttpClient client, HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
