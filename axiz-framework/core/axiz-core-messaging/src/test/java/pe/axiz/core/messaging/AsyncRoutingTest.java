package pe.axiz.core.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncRoutingTest {
  private static final EventEnvelope EVENT =
      new EventEnvelope(
          "id",
          "urn:service",
          "type",
          Instant.EPOCH,
          null,
          "application/json",
          new byte[0],
          Map.of());

  @Test
  void selectsFirstSupportingPublisherAndCancelsItsExactOperation() {
    CompletableFuture<PublishResult> nativeCall = new CompletableFuture<>();
    AtomicInteger unsupportedCalls = new AtomicInteger();
    AsyncEventPublisher unsupported =
        new AsyncEventPublisher() {
          public boolean supports(String destination) {
            return false;
          }

          public CompletionStage<PublishResult> publish(String destination, EventEnvelope event) {
            unsupportedCalls.incrementAndGet();
            throw new AssertionError();
          }
        };
    var publisher =
        new RoutingAsyncEventPublisher(List.of(unsupported, (destination, event) -> nativeCall));
    assertTrue(publisher.supports("queue"));
    var result = publisher.publish("queue", EVENT).toCompletableFuture();
    assertSame(nativeCall, result);
    assertFalse(result.isDone());
    assertTrue(result.cancel(true));
    assertTrue(nativeCall.isCancelled());
    assertEquals(0, unsupportedCalls.get());
  }

  @Test
  void unavailableDestinationAndSubmissionFailureNeverTryAnotherBroker() {
    var empty = new RoutingAsyncEventPublisher(List.of());
    assertFalse(empty.supports("queue"));
    assertInstanceOfCause(
        IllegalArgumentException.class, empty.publish("queue", EVENT).toCompletableFuture());
    AtomicInteger fallbackCalls = new AtomicInteger();
    IllegalStateException original = new IllegalStateException("unavailable");
    var failed =
        new RoutingAsyncEventPublisher(
            List.of(
                (destination, event) -> {
                  throw original;
                },
                (destination, event) -> {
                  fallbackCalls.incrementAndGet();
                  return CompletableFuture.completedFuture(
                      new PublishResult(event.id(), destination, null));
                }));
    assertSame(
        original,
        assertThrows(
                CompletionException.class,
                () -> failed.publish("queue", EVENT).toCompletableFuture().join())
            .getCause());
    assertEquals(0, fallbackCalls.get());
  }

  private static void assertInstanceOfCause(
      Class<? extends Throwable> type, CompletableFuture<?> future) {
    assertTrue(type.isInstance(assertThrows(CompletionException.class, future::join).getCause()));
  }
}
