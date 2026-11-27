package pe.axiz.adapter.tokenization.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SerialRedisDispatchTest {
  @Test
  void capacityAndCancellationNeverStartAnOverlappingCommand() {
    var queue = new SerialRedisDispatch(2, Duration.ofSeconds(2));
    var accepted = new CompletableFuture<String>();
    var first = queue.submit(() -> accepted).toCompletableFuture();
    var started = new AtomicInteger();
    var next =
        queue.submit(
            () -> {
              started.incrementAndGet();
              return CompletableFuture.completedFuture("second");
            });
    var rejected = queue.submit(() -> CompletableFuture.completedFuture("third"));
    assertInstanceOf(
        RejectedExecutionException.class,
        assertThrows(CompletionException.class, () -> rejected.toCompletableFuture().join())
            .getCause());
    first.cancel(true);
    assertFalse(accepted.isCancelled());
    assertEquals(0, started.get());
    accepted.complete("first");
    assertEquals("second", next.toCompletableFuture().join());
    assertEquals(1, started.get());
  }

  @Test
  void expiredQueuedWorkNeverReachesRedisAndLaterWorkCanProceed() {
    var ticker = new AtomicLong();
    var queue = new SerialRedisDispatch(4, Duration.ofNanos(100), ticker::get);
    var accepted = new CompletableFuture<String>();
    queue.submit(() -> accepted);
    var called = new AtomicInteger();
    var waiting =
        queue.submit(
            () -> {
              called.incrementAndGet();
              return CompletableFuture.completedFuture("late");
            });
    ticker.set(101);
    accepted.complete("first");
    assertEquals(0, called.get());
    assertInstanceOf(
        TimeoutException.class,
        assertThrows(CompletionException.class, () -> waiting.toCompletableFuture().join())
            .getCause());
    assertEquals(
        "fresh",
        queue
            .submit(() -> CompletableFuture.completedFuture("fresh"))
            .toCompletableFuture()
            .join());
  }

  @Test
  void reentrantEnqueueWaitsUntilNativeSubmissionHasReturned() {
    var queue = new SerialRedisDispatch(4, Duration.ofSeconds(2));
    var called = new AtomicInteger();
    var first =
        queue.submit(
            () -> {
              queue.submit(
                  () -> {
                    called.incrementAndGet();
                    return CompletableFuture.completedFuture("nested");
                  });
              assertEquals(0, called.get());
              return CompletableFuture.completedFuture("outer");
            });
    assertEquals("outer", first.toCompletableFuture().join());
    assertEquals(1, called.get());
  }
}
