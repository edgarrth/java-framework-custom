package pe.axiz.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsyncStorageTest {
  private static Flow.Publisher<ByteBuffer> bytes(byte... bytes) {
    return target ->
        target.onSubscribe(
            new Flow.Subscription() {
              private boolean done;

              @Override
              public void request(long n) {
                if (done) return;
                done = true;
                target.onNext(ByteBuffer.wrap(bytes));
                target.onComplete();
              }

              @Override
              public void cancel() {
                done = true;
              }
            });
  }

  @Test
  void nativeLocalStoragePreservesMetadataAndPublishesOnlyOnDemand() {
    var storage = new InMemoryAsyncObjectStorage(8);
    storage
        .put(
            "in/a",
            bytes((byte) 7, (byte) 8),
            new ObjectMetadata("text/plain", 2, null, Map.of("origin", "test")))
        .toCompletableFuture()
        .join();
    assertEquals(List.of("in/a"), storage.list("in/", 1).toCompletableFuture().join());
    assertEquals(
        2, storage.head("in/a").toCompletableFuture().join().orElseThrow().contentLength());
    var object = storage.get("in/a").toCompletableFuture().join().orElseThrow();
    assertEquals("in/a", object.key());
    assertEquals("text/plain", object.metadata().contentType());
    assertEquals(Map.of("origin", "test"), object.metadata().userMetadata());
    assertNotNull(object.metadata().checksumSha256());
    var subscriber = new Probe();
    object.content().subscribe(subscriber);
    assertEquals(0, subscriber.chunks.get());
    subscriber.subscription.get().request(1);
    assertEquals(1, subscriber.chunks.get());
    assertEquals(1, subscriber.completed.get());
    assertTrue(subscriber.value.get().isReadOnly());
    assertEquals(7, subscriber.value.get().get());
    object.close();
    var second = new Probe();
    object.content().subscribe(second);
    assertInstanceOf(IllegalStateException.class, second.failure.get());
    assertTrue(
        storage
            .presignGet("in/a", Duration.ofMinutes(1))
            .toCompletableFuture()
            .join()
            .toString()
            .contains("op=get"));
    assertTrue(
        storage
            .presignPut("in/a", "text/plain", Duration.ofMinutes(1))
            .toCompletableFuture()
            .join()
            .toString()
            .contains("op=put"));
    storage.delete("in/a").toCompletableFuture().join();
    assertTrue(storage.get("in/a").toCompletableFuture().join().isEmpty());
    assertTrue(storage.head("in/a").toCompletableFuture().join().isEmpty());
  }

  @Test
  void invalidOrFailedUploadsNeverReplaceTheExistingObjectAndCancellationReachesThePublisher() {
    var storage = new InMemoryAsyncObjectStorage(2);
    storage.put("x", bytes((byte) 1), ObjectMetadata.of("t", 1)).toCompletableFuture().join();
    assertThrows(IllegalArgumentException.class, () -> new InMemoryAsyncObjectStorage(0));
    for (int length : new int[] {-1, 3, 0, 2}) {
      assertThrows(
          CompletionException.class,
          () ->
              storage
                  .put("x", bytes((byte) 4), ObjectMetadata.of("t", length))
                  .toCompletableFuture()
                  .join());
    }
    assertThrows(
        CompletionException.class,
        () ->
            storage
                .put("x", bytes((byte) 4), new ObjectMetadata("t", 1, "wrong", Map.of()))
                .toCompletableFuture()
                .join());
    RuntimeException problem = new IllegalStateException("source");
    assertSame(
        problem,
        assertThrows(
                CompletionException.class,
                () ->
                    storage
                        .put(
                            "x",
                            target -> {
                              target.onSubscribe(
                                  new Flow.Subscription() {
                                    @Override
                                    public void request(long n) {
                                      target.onError(problem);
                                    }

                                    @Override
                                    public void cancel() {}
                                  });
                            },
                            ObjectMetadata.of("t", 1))
                        .toCompletableFuture()
                        .join())
            .getCause());
    assertEquals(1, storage.head("x").toCompletableFuture().join().orElseThrow().contentLength());
    AtomicInteger cancellations = new AtomicInteger();
    var pending =
        storage
            .put(
                "x",
                target ->
                    target.onSubscribe(
                        new Flow.Subscription() {
                          @Override
                          public void request(long n) {}

                          @Override
                          public void cancel() {
                            cancellations.incrementAndGet();
                          }
                        }),
                ObjectMetadata.of("t", 1))
            .toCompletableFuture();
    assertTrue(pending.cancel(true));
    assertEquals(1, cancellations.get());
    assertThrows(CompletionException.class, () -> storage.get("../x").toCompletableFuture().join());
    assertThrows(
        CompletionException.class, () -> storage.delete("../x").toCompletableFuture().join());
    assertThrows(
        CompletionException.class, () -> storage.head("../x").toCompletableFuture().join());
    assertThrows(
        CompletionException.class, () -> storage.list("../", 1).toCompletableFuture().join());
    assertThrows(
        CompletionException.class, () -> storage.list("", -1).toCompletableFuture().join());
    assertEquals(List.of(), storage.list("", 0).toCompletableFuture().join());
    assertThrows(
        CompletionException.class,
        () -> storage.presignGet("x", Duration.ZERO).toCompletableFuture().join());
  }

  @Test
  void closingAnUnconsumedResponseAbortsOnlyOnceAndInvalidDemandFails() {
    AtomicInteger aborts = new AtomicInteger();
    var object =
        new AsyncStoredObject("x", ObjectMetadata.of("t", 0), bytes(), aborts::incrementAndGet);
    object.close();
    object.close();
    assertEquals(1, aborts.get());
    var storage = new InMemoryAsyncObjectStorage();
    storage.put("x", bytes((byte) 1), ObjectMetadata.of("t", 1)).toCompletableFuture().join();
    var response = storage.get("x").toCompletableFuture().join().orElseThrow();
    var probe = new Probe();
    response.content().subscribe(probe);
    probe.subscription.get().request(0);
    assertInstanceOf(IllegalArgumentException.class, probe.failure.get());
    assertEquals(0, probe.completed.get());
    var closed = storage.get("x").toCompletableFuture().join().orElseThrow();
    closed.close();
    var rejected = new Probe();
    closed.content().subscribe(rejected);
    assertInstanceOf(IllegalStateException.class, rejected.failure.get());
    var cancelled = storage.get("x").toCompletableFuture().join().orElseThrow();
    var cancelledProbe = new Probe();
    cancelled.content().subscribe(cancelledProbe);
    cancelledProbe.subscription.get().cancel();
    cancelledProbe.subscription.get().request(1);
    assertEquals(0, cancelledProbe.chunks.get());
  }

  static class Probe implements Flow.Subscriber<ByteBuffer> {
    final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    final AtomicInteger chunks = new AtomicInteger();
    final AtomicInteger completed = new AtomicInteger();
    final AtomicReference<ByteBuffer> value = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
      subscription.set(incoming);
    }

    @Override
    public void onNext(ByteBuffer chunk) {
      chunks.incrementAndGet();
      value.set(chunk);
    }

    @Override
    public void onError(Throwable problem) {
      failure.set(problem);
    }

    @Override
    public void onComplete() {
      completed.incrementAndGet();
    }
  }
}
