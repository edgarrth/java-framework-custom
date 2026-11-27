package pe.axiz.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.storage.AsyncObjectStorage;
import pe.axiz.core.storage.AsyncStoredObject;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ReactiveStorageTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class));
  }

  @Test
  void memoryWiringSelectsOnlyTheNativeFacadeAndOperationsAreDeferred() {
    reactive()
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=local")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(AsyncObjectStorage.class)
                  .hasSingleBean(ReactiveStorage.class)
                  .doesNotHaveBean(ObjectStorage.class);
              ReactiveStorage storage = context.getBean(ReactiveStorage.class);
              var upload =
                  storage.put(
                      "x", Flux.just(ByteBuffer.wrap(new byte[] {7})), ObjectMetadata.of("t", 1));
              StepVerifier.create(storage.head("x")).verifyComplete();
              StepVerifier.create(upload).verifyComplete();
              StepVerifier.create(storage.head("x"))
                  .assertNext(meta -> assertEquals(1, meta.contentLength()))
                  .verifyComplete();
              StepVerifier.create(storage.list("", 1))
                  .assertNext(keys -> assertEquals(List.of("x"), keys))
                  .verifyComplete();
              StepVerifier.create(storage.content("x"))
                  .assertNext(bytes -> assertEquals(7, bytes.get()))
                  .verifyComplete();
              StepVerifier.create(storage.get("x"))
                  .assertNext(AsyncStoredObject::close)
                  .verifyComplete();
              StepVerifier.create(storage.presignGet("x", Duration.ofSeconds(1)))
                  .expectNextCount(1)
                  .verifyComplete();
              StepVerifier.create(storage.presignUpload("x", "t", Duration.ofSeconds(1)))
                  .expectNextCount(1)
                  .verifyComplete();
              StepVerifier.create(storage.presignUpload("x", "t", Duration.ofSeconds(1)))
                  .assertNext(
                      instructions ->
                          assertEquals(List.of("t"), instructions.headers().get("Content-Type")))
                  .verifyComplete();
              StepVerifier.create(storage.delete("x")).verifyComplete();
              StepVerifier.create(storage.content("x")).verifyComplete();
            });
    reactive()
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=prod")
        .run(context -> assertThat(context).hasFailed());
    reactive().run(context -> assertThat(context).hasFailed());
  }

  @Test
  void lateHeadersAreDiscardedAndClosedAfterDownstreamCancellation() {
    AsyncObjectStorage port = mock(AsyncObjectStorage.class);
    CompletableFuture<Optional<AsyncStoredObject>> headers =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    when(port.get("x")).thenReturn(headers);
    AtomicInteger closes = new AtomicInteger();
    var subscription =
        new ReactiveStorage(port).get("x").subscribe(object -> fail("Cancelled subscriber"));
    subscription.dispose();
    headers.complete(
        Optional.of(
            new AsyncStoredObject(
                "x",
                ObjectMetadata.of("t", 0),
                JdkFlowAdapter.publisherToFlowPublisher(Flux.empty()),
                closes::incrementAndGet)));
    assertEquals(1, closes.get());
  }

  @Test
  void completionErrorAndCancellationAllCloseTheDownloadAndPendingHeadersCancelNatively() {
    AsyncObjectStorage port = mock(AsyncObjectStorage.class);
    AtomicInteger closes = new AtomicInteger();
    when(port.get("x"))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    Optional.of(
                        new AsyncStoredObject(
                            "x",
                            ObjectMetadata.of("t", 1),
                            JdkFlowAdapter.publisherToFlowPublisher(
                                Flux.just(ByteBuffer.wrap(new byte[] {1}))),
                            closes::incrementAndGet))));
    var storage = new ReactiveStorage(port);
    StepVerifier.create(storage.content("x")).expectNextCount(1).verifyComplete();
    assertEquals(1, closes.get());
    when(port.get("x"))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    Optional.of(
                        new AsyncStoredObject(
                            "x",
                            ObjectMetadata.of("t", 1),
                            JdkFlowAdapter.publisherToFlowPublisher(
                                Flux.error(new IllegalStateException("body"))),
                            closes::incrementAndGet))));
    StepVerifier.create(storage.content("x")).verifyError(IllegalStateException.class);
    assertEquals(2, closes.get());
    when(port.get("x"))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    Optional.of(
                        new AsyncStoredObject(
                            "x",
                            ObjectMetadata.of("t", 1),
                            JdkFlowAdapter.publisherToFlowPublisher(
                                Flux.concat(Flux.just(ByteBuffer.allocate(1)), Flux.never())),
                            closes::incrementAndGet))));
    StepVerifier.create(storage.content("x")).expectNextCount(1).thenCancel().verify();
    assertEquals(3, closes.get());
    var headers = new CompletableFuture<Optional<AsyncStoredObject>>();
    when(port.get("x")).thenReturn(headers);
    StepVerifier.create(storage.get("x")).thenCancel().verify();
    assertTrue(headers.isCancelled());
  }
}
