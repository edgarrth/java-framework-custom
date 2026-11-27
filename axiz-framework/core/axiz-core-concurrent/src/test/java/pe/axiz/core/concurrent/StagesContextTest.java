package pe.axiz.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StagesContextTest {
  private static final ThreadLocal<String> VALUE = new ThreadLocal<>();
  private static final Stages.ContextProvider PROVIDER =
      () -> {
        String captured = VALUE.get();
        return () -> {
          String before = VALUE.get();
          restore(captured);
          return () -> restore(before);
        };
      };

  private static void restore(String value) {
    if (value == null) VALUE.remove();
    else VALUE.set(value);
  }

  @Test
  void independentCallbacksAndNestedCallsKeepTheirContextAndRestoreTheWorker() throws Exception {
    var nativeRequest = new CompletableFuture<String>();
    CompletableFuture<String> first;
    CompletableFuture<String> second;
    VALUE.set("request-a");
    try (var scope = Stages.context(PROVIDER)) {
      first =
          Stages.compose(
                  nativeRequest,
                  ignored -> {
                    assertEquals("request-a", VALUE.get());
                    VALUE.set("nested-a");
                    return Stages.map(
                        CompletableFuture.completedFuture("done"), result -> VALUE.get());
                  })
              .toCompletableFuture();
    }
    VALUE.set("request-b");
    try (var scope = Stages.context(PROVIDER)) {
      second = Stages.map(nativeRequest, ignored -> VALUE.get()).toCompletableFuture();
    }
    VALUE.remove();
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(
              () -> {
                VALUE.set("worker");
                nativeRequest.complete("value");
                assertEquals("worker", VALUE.get());
                VALUE.remove();
              })
          .get(5, TimeUnit.SECONDS);
    }
    assertEquals("nested-a", first.join());
    assertEquals("request-b", second.join());
    assertNull(VALUE.get());
  }

  @Test
  void coalescedCacheListenersBelongToEachCallerRatherThanToTheFirstLoad() throws Exception {
    var cache = new AsyncLoadingCache<String>(Duration.ofMinutes(1), 10, 2, Clock.systemUTC());
    var nativeRequest = new CompletableFuture<String>();
    CompletableFuture<String> first;
    CompletableFuture<String> second;
    VALUE.set("request-a");
    try (var scope = Stages.context(PROVIDER)) {
      first =
          cache
              .get("key", () -> nativeRequest)
              .thenApply(
                  value -> {
                    assertEquals("request-a", VALUE.get());
                    return value;
                  })
              .toCompletableFuture();
    }
    VALUE.set("request-b");
    try (var scope = Stages.context(PROVIDER)) {
      second =
          cache
              .get(
                  "key",
                  () -> {
                    throw new AssertionError("duplicate load");
                  })
              .thenApply(
                  value -> {
                    assertEquals("request-b", VALUE.get());
                    return value;
                  })
              .toCompletableFuture();
    }
    VALUE.remove();
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(
              () -> {
                VALUE.set("worker");
                nativeRequest.complete("value");
                assertEquals("worker", VALUE.get());
                VALUE.remove();
              })
          .get(5, TimeUnit.SECONDS);
    }
    assertEquals("value", first.join());
    assertEquals("value", second.join());
    assertNull(VALUE.get());
  }

  @Test
  void contextCaptureAndScopeFailuresCompleteOutputsInsteadOfLeavingThemPending() {
    var failure = new IllegalStateException("context failed");
    var source = new CompletableFuture<String>();
    var cache = new AsyncLoadingCache<String>(Duration.ofMinutes(1), 10, 1, Clock.systemUTC());
    try (var scope =
        Stages.context(
            () -> {
              throw failure;
            })) {
      var result = Stages.map(source, value -> value).toCompletableFuture();
      assertTrue(result.isCompletedExceptionally());
      assertTrue(source.isCancelled(), "failed context setup must release pending native I/O");
      assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
      var cached =
          cache
              .get(
                  "key",
                  () -> {
                    throw new AssertionError("no caller remains");
                  })
              .toCompletableFuture();
      assertTrue(cached.isCompletedExceptionally());
    }
    assertEquals(
        "available",
        cache
            .get("next", () -> CompletableFuture.completedFuture("available"))
            .toCompletableFuture()
            .join());
    CompletableFuture<String> result;
    var scopeSource = new CompletableFuture<String>();
    try (var scope =
        Stages.context(
            () ->
                () -> {
                  throw failure;
                })) {
      result = Stages.map(scopeSource, value -> value).toCompletableFuture();
    }
    scopeSource.complete("done");
    assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    assertEquals(
        "clean",
        Stages.map(CompletableFuture.completedFuture("clean"), value -> value)
            .toCompletableFuture()
            .join());
  }
}
