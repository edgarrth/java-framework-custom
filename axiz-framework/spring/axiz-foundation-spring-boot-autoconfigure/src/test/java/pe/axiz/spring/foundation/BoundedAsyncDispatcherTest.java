package pe.axiz.spring.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedAsyncDispatcherTest {
  @Test
  void boundsUnacknowledgedSendsAndReturnsCapacityOnAck() throws Exception {
    var registry = new SimpleMeterRegistry();
    try (var dispatcher =
        new BoundedAsyncDispatcher("test", 1, 1, Duration.ofSeconds(5), registry)) {
      var ack = new CompletableFuture<String>();
      var entered = new CountDownLatch(1);
      var first =
          dispatcher
              .submit(
                  () -> {
                    entered.countDown();
                    return ack;
                  })
              .toCompletableFuture();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var secondAck = new CompletableFuture<String>();
      var secondEntered = new CountDownLatch(1);
      var second =
          dispatcher
              .submit(
                  () -> {
                    secondEntered.countDown();
                    return secondAck;
                  })
              .toCompletableFuture();
      assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
      assertThrows(
          java.util.concurrent.CompletionException.class,
          () ->
              dispatcher
                  .submit(() -> CompletableFuture.completedFuture("overflow"))
                  .toCompletableFuture()
                  .join());
      assertEquals(1, registry.get("axiz.transport.rejected").counter().count());
      assertEquals(2, registry.get("axiz.transport.pending").gauge().value());
      ack.complete("ok");
      assertEquals("ok", first.get(2, TimeUnit.SECONDS));
      assertEquals(
          "reused",
          dispatcher
              .submit(() -> CompletableFuture.completedFuture("reused"))
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS));
      assertEquals(1, registry.get("axiz.transport.pending").gauge().value());
      second.cancel(false);
      assertFalse(secondAck.isCancelled());
      assertEquals(1, registry.get("axiz.transport.pending").gauge().value());
      secondAck.complete("late");
      assertEquals(0, registry.get("axiz.transport.pending").gauge().value());
      assertEquals(3, registry.get("axiz.transport.latency").timer().count());
      assertTrue(registry.get("axiz.transport.latency").timer().totalTime(TimeUnit.SECONDS) < 10);
    }
  }

  @Test
  void timedOutAcceptedSendStillOccupiesCapacityUntilBrokerCompletes() throws Exception {
    var registry = new SimpleMeterRegistry();
    try (var dispatcher =
        new BoundedAsyncDispatcher("test", 1, 0, Duration.ofMillis(100), registry)) {
      var ack = new CompletableFuture<String>();
      var entered = new CountDownLatch(1);
      var result =
          dispatcher
              .submit(
                  () -> {
                    entered.countDown();
                    return ack;
                  })
              .toCompletableFuture();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertInstanceOf(
          java.util.concurrent.TimeoutException.class,
          assertThrows(
                  java.util.concurrent.ExecutionException.class,
                  () -> result.get(2, TimeUnit.SECONDS))
              .getCause());
      assertThrows(
          java.util.concurrent.CompletionException.class,
          () ->
              dispatcher
                  .submit(() -> CompletableFuture.completedFuture("overflow"))
                  .toCompletableFuture()
                  .join());
      assertFalse(ack.isCancelled());
      ack.complete("late-ack");
    }
  }

  @Test
  void cancelsQueuedWorkBeforeItReachesTransportAndCloseFailsPending() throws Exception {
    var gate = new CountDownLatch(1);
    var registry = new SimpleMeterRegistry();
    try (var dispatcher =
        new BoundedAsyncDispatcher("test", 1, 1, Duration.ofSeconds(5), registry)) {
      var entered = new CountDownLatch(1);
      var first =
          dispatcher
              .submit(
                  () -> {
                    entered.countDown();
                    try {
                      assertTrue(gate.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    return new CompletableFuture<String>();
                  })
              .toCompletableFuture();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var called = new AtomicBoolean();
      var queued =
          dispatcher
              .submit(
                  () -> {
                    called.set(true);
                    return CompletableFuture.completedFuture("bad");
                  })
              .toCompletableFuture();
      assertTrue(queued.cancel(false));
      gate.countDown();
      dispatcher.close();
      assertTrue(first.isCompletedExceptionally());
      assertFalse(called.get());
      assertThrows(
          java.util.concurrent.CompletionException.class,
          () ->
              dispatcher
                  .submit(() -> CompletableFuture.completedFuture("closed"))
                  .toCompletableFuture()
                  .join());
    } finally {
      gate.countDown();
    }
  }

  @Test
  void immediateTransportFailureIsReportedAndDoesNotLeakCapacity() throws Exception {
    var registry = new SimpleMeterRegistry();
    try (var dispatcher =
        new BoundedAsyncDispatcher("test", 1, 1, Duration.ofSeconds(5), registry)) {
      var failure = new IllegalArgumentException("transport rejected");
      var result =
          dispatcher
              .<String>submit(
                  () -> {
                    throw failure;
                  })
              .toCompletableFuture();
      assertSame(
          failure,
          assertThrows(
                  java.util.concurrent.ExecutionException.class,
                  () -> result.get(2, TimeUnit.SECONDS))
              .getCause());
      assertEquals(0, registry.get("axiz.transport.pending").gauge().value());
    }
  }

  @Test
  void closingClearsPendingMetricsAndAllowsARegistryToBeReused() throws Exception {
    var registry = new SimpleMeterRegistry();
    var dispatcher = new BoundedAsyncDispatcher("lifecycle", 1, 0, Duration.ofSeconds(5), registry);
    var entered = new CountDownLatch(1);
    var pending =
        dispatcher.submit(
            () -> {
              entered.countDown();
              return new CompletableFuture<String>();
            });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    var gauge = registry.get("axiz.transport.pending").gauge();
    assertEquals(1, gauge.value());
    dispatcher.close();
    dispatcher.close();
    assertTrue(pending.toCompletableFuture().isCompletedExceptionally());
    assertEquals(0, gauge.value());
    assertTrue(registry.getMeters().isEmpty());
    try (var next =
        new BoundedAsyncDispatcher("lifecycle", 1, 0, Duration.ofSeconds(5), registry)) {
      assertEquals(
          "ok",
          next.submit(() -> CompletableFuture.completedFuture("ok"))
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS));
      assertEquals(1, registry.get("axiz.transport.latency").timer().count());
    }
  }
}
