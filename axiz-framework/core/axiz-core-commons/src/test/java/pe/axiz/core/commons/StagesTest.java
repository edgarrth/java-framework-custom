package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StagesTest {
  @Test
  void requiredValuesRejectEmptyCompletionsAndPreserveFailuresAndCancellation() {
    CompletableFuture<@Nullable String> success = CompletableFuture.completedFuture("value");
    assertEquals("value", Stages.requireValue(success).toCompletableFuture().join());
    CompletableFuture<@Nullable String> empty = CompletableFuture.completedFuture(null);
    assertTrue(
        assertThrows(
                    CompletionException.class,
                    () -> Stages.requireValue(empty).toCompletableFuture().join())
                .getCause()
            instanceof IllegalStateException);
    var failure = new IllegalArgumentException("native failure");
    CompletableFuture<@Nullable String> failed =
        CompletableFuture.failedFuture(new CompletionException(failure));
    assertSame(
        failure,
        assertThrows(
                CompletionException.class,
                () -> Stages.requireValue(failed).toCompletableFuture().join())
            .getCause());
    CompletableFuture<@Nullable String> pending = new CompletableFuture<>();
    assertTrue(Stages.requireValue(pending).toCompletableFuture().cancel(true));
    assertTrue(pending.isCancelled());
  }

  @Test
  void submissionFailureIsAStageAndNativeCancellationIsPreserved() {
    var nativeFuture = new CompletableFuture<String>();
    assertSame(nativeFuture, Stages.call(() -> nativeFuture));
    var pending = Stages.call(() -> nativeFuture);
    pending.toCompletableFuture().cancel(true);
    assertTrue(nativeFuture.isCancelled());
    var backend = new IllegalStateException("submission rejected");
    var failed =
        Stages.call(
            () -> {
              throw backend;
            });
    assertSame(
        backend,
        assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join())
            .getCause());
  }

  @Test
  void voidStagesKeepCancellationAndFailureCleanup() {
    var nativeFuture = new CompletableFuture<String>();
    var ignored = Stages.ignore(nativeFuture);
    ignored.toCompletableFuture().cancel(true);
    assertTrue(nativeFuture.isCancelled());
    var failure = new IllegalStateException("write failed");
    var observed = new AtomicReference<Throwable>();
    Stages.settle(CompletableFuture.failedFuture(failure), observed::set)
        .toCompletableFuture()
        .join();
    assertSame(failure, observed.get());
    var cleanup = new CompletableFuture<Void>();
    Stages.settle(cleanup, observed::set).toCompletableFuture().cancel(true);
    assertTrue(cleanup.isCancelled());
  }

  @Test
  void cancellationBeforeFirstCompletionNeverStartsTheSecondOperation() {
    CompletableFuture<String> first = new CompletableFuture<>();
    AtomicBoolean ran = new AtomicBoolean();
    var result =
        Stages.compose(
            first,
            value -> {
              ran.set(true);
              return CompletableFuture.completedFuture(value);
            });
    assertTrue(result.toCompletableFuture().cancel(true));
    assertTrue(first.isCancelled());
    first.complete("late");
    assertFalse(ran.get());
  }

  @Test
  void cancellationAfterTransitionReachesTheActiveNativeOperation() {
    CompletableFuture<String> first = new CompletableFuture<>();
    CompletableFuture<Integer> second = new CompletableFuture<>();
    var result = Stages.compose(first, value -> second);
    first.complete("first");
    result.toCompletableFuture().cancel(true);
    assertTrue(second.isCancelled());
  }

  @Test
  void sourceAndMapperFailuresKeepTheOriginalCause() {
    IllegalStateException cause = new IllegalStateException("backend");
    var failed = Stages.map(CompletableFuture.<String>failedFuture(cause), String::length);
    assertSame(
        cause,
        assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join())
            .getCause());
    var mapper =
        Stages.map(
            CompletableFuture.completedFuture("ok"),
            value -> {
              throw cause;
            });
    assertSame(
        cause,
        assertThrows(CompletionException.class, () -> mapper.toCompletableFuture().join())
            .getCause());
  }

  @Test
  void recoveryCannotDetachTheNativeRequestFromCancellation() {
    CompletableFuture<String> source = new CompletableFuture<>();
    var result = Stages.recover(source, error -> "fallback");
    result.toCompletableFuture().cancel(true);
    assertTrue(source.isCancelled());
  }

  @Test
  void successfulCompositionAndRecoveryRetainTheirValues() {
    assertEquals(
        4,
        Stages.map(CompletableFuture.completedFuture("test"), String::length)
            .toCompletableFuture()
            .join());
    assertEquals(
        "fallback",
        Stages.recover(
                CompletableFuture.<String>failedFuture(new IllegalStateException()),
                error -> "fallback")
            .toCompletableFuture()
            .join());
  }
}
