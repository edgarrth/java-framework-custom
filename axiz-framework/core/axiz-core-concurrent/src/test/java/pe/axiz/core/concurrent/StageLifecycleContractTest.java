package pe.axiz.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StageLifecycleContractTest {
  @Test
  void cancellationResultsAndPropagationRemainCorrectBeforeAndAfterCompletion() {
    List<Function<CompletableFuture<String>, CompletionStage<?>>> adapters =
        List.of(
            source -> Stages.consume(source, value -> value, value -> {}, value -> {}),
            source -> Stages.requireValue(source),
            source -> Stages.compose(source, CompletableFuture::completedFuture),
            source -> Stages.recover(source, error -> "fallback"),
            source -> Stages.ignore(source));
    for (var adapter : adapters) {
      var source = new CompletableFuture<String>();
      var result = adapter.apply(source).toCompletableFuture();
      assertTrue(result.cancel(false));
      assertTrue(source.isCancelled());
      var completed = new CompletableFuture<String>();
      var output = adapter.apply(completed).toCompletableFuture();
      assertTrue(completed.complete("delivered"));
      output.join();
      assertFalse(output.cancel(true));
      assertFalse(completed.isCancelled());
    }
    var pending = new CompletableFuture<Void>();
    var settled = Stages.settle(pending, error -> {}).toCompletableFuture();
    assertTrue(settled.cancel(true));
    assertTrue(pending.isCancelled());
    var done =
        Stages.settle(CompletableFuture.completedFuture(null), error -> {}).toCompletableFuture();
    assertNull(done.join());
    assertFalse(done.cancel(true));
    assertNull(
        Stages.ignore(CompletableFuture.completedFuture("secret")).toCompletableFuture().join());
  }

  @Test
  void cleanupFailuresPreservePrimaryCauseAndDiscardAnyUndeliveredMappedValue() {
    var mapping = new IllegalStateException("mapping");
    var cleanup = new IllegalArgumentException("cleanup");
    var result =
        Stages.consume(
                CompletableFuture.completedFuture("owned"),
                value -> {
                  throw mapping;
                },
                value -> {
                  throw cleanup;
                },
                value -> {})
            .toCompletableFuture();
    assertSame(mapping, assertThrows(CompletionException.class, result::join).getCause());
    assertEquals(List.of(cleanup), List.of(mapping.getSuppressed()));
    var discarded = new AtomicInteger();
    var released =
        Stages.consume(
                CompletableFuture.completedFuture("owned"),
                value -> "mapped",
                value -> {
                  throw cleanup;
                },
                value -> {
                  assertEquals("mapped", value);
                  discarded.incrementAndGet();
                })
            .toCompletableFuture();
    assertSame(cleanup, assertThrows(CompletionException.class, released::join).getCause());
    assertEquals(1, discarded.get());
    var same = new IllegalStateException("same");
    var repeated =
        Stages.consume(
                CompletableFuture.completedFuture("owned"),
                value -> {
                  throw same;
                },
                value -> {
                  throw same;
                },
                value -> {})
            .toCompletableFuture();
    assertSame(same, assertThrows(CompletionException.class, repeated::join).getCause());
    assertEquals(0, same.getSuppressed().length);
    var noCause = new CompletionException(null);
    assertSame(noCause, Stages.unwrap(noCause));
    var ordinary = new IllegalStateException("ordinary", cleanup);
    assertSame(ordinary, Stages.unwrap(ordinary));
  }

  @Test
  void closingANestedContextRestoresTheOuterProviderAndThenRemovesIt() {
    var outerCaptures = new AtomicInteger();
    var innerCaptures = new AtomicInteger();
    try (var outer =
        Stages.context(
            () -> {
              outerCaptures.incrementAndGet();
              return () -> () -> {};
            })) {
      Stages.ignore(CompletableFuture.completedFuture(1)).toCompletableFuture().join();
      int outerBefore = outerCaptures.get();
      try (var inner =
          Stages.context(
              () -> {
                innerCaptures.incrementAndGet();
                return () -> () -> {};
              })) {
        Stages.ignore(CompletableFuture.completedFuture(2)).toCompletableFuture().join();
        assertEquals(outerBefore, outerCaptures.get());
      }
      int innerBefore = innerCaptures.get();
      assertTrue(innerBefore > 0);
      Stages.ignore(CompletableFuture.completedFuture(3)).toCompletableFuture().join();
      assertTrue(outerCaptures.get() > outerBefore);
      assertEquals(innerBefore, innerCaptures.get());
    }
    int before = outerCaptures.get();
    Stages.ignore(CompletableFuture.completedFuture(4)).toCompletableFuture().join();
    assertEquals(before, outerCaptures.get());
  }

  @Test
  void everyContextSetupFailureCompletesTheResultAndCancelsItsNativeOperation() {
    var failure = new IllegalStateException("context setup");
    try (var context =
        Stages.context(
            () -> {
              throw failure;
            })) {
      var required = new CompletableFuture<String>();
      var recovered = new CompletableFuture<String>();
      var settled = new CompletableFuture<Void>();
      var results =
          List.of(
              Stages.requireValue(required),
              Stages.recover(recovered, error -> "fallback"),
              Stages.settle(settled, error -> {}));
      for (var result : results) {
        assertTrue(result.toCompletableFuture().isDone());
        assertSame(
            failure,
            assertThrows(CompletionException.class, () -> result.toCompletableFuture().join())
                .getCause());
      }
      assertTrue(required.isCancelled());
      assertTrue(recovered.isCancelled());
      assertTrue(settled.isCancelled());
    }
    var count = new AtomicInteger();
    var next = new CompletableFuture<String>();
    try (var context =
        Stages.context(
            () -> {
              if (count.incrementAndGet() == 2) throw failure;
              return () -> () -> {};
            })) {
      var result =
          Stages.compose(CompletableFuture.completedFuture("first"), value -> next)
              .toCompletableFuture();
      assertTrue(result.isDone());
      assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
      assertTrue(next.isCancelled());
    }
  }
}
