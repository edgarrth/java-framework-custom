package pe.axiz.core.sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AsyncSequenceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-31T15:00:00Z"), ZoneOffset.UTC);
  private static final SequenceFormat FORMAT =
      new SequenceFormat("OP-", 6, ResetPolicy.NEVER, "-", ZoneOffset.UTC);

  @Test
  void sharesPendingReservationAndOneCancellationDoesNotCancelOtherCallers() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<Long> nativeCall = new CompletableFuture<>();
    AsyncSequenceGenerator sequence =
        new AsyncSequenceGenerator(
            "operation",
            FORMAT,
            (name, period, amount) -> {
              calls.incrementAndGet();
              return nativeCall;
            },
            4,
            CLOCK);
    var cancelled = sequence.nextValue().toCompletableFuture();
    var second = sequence.nextValue().toCompletableFuture();
    var third = sequence.nextValue().toCompletableFuture();
    assertEquals(1, calls.get());
    assertTrue(cancelled.cancel(true));
    assertFalse(nativeCall.isCancelled());
    nativeCall.complete(4L);
    assertEquals(Set.of(1L, 2L), Set.of(second.join(), third.join()));
    assertEquals(3L, sequence.nextValue().toCompletableFuture().join());
    assertEquals("OP-000004", sequence.next().toCompletableFuture().join());
    assertEquals(1, calls.get());
  }

  @Test
  void lastCancellationCancelsNativeIoAndANewCallerCanRetry() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<Long> nativeCall = new CompletableFuture<>();
    AsyncSequenceGenerator sequence =
        new AsyncSequenceGenerator(
            "operation",
            FORMAT,
            (name, period, amount) ->
                calls.incrementAndGet() == 1 ? nativeCall : CompletableFuture.completedFuture(8L),
            4,
            CLOCK);
    var first = sequence.nextValue().toCompletableFuture();
    var second = sequence.nextValue().toCompletableFuture();
    first.cancel(true);
    assertFalse(nativeCall.isCancelled());
    second.cancel(true);
    assertTrue(nativeCall.isCancelled());
    assertEquals(5L, sequence.nextValue().toCompletableFuture().join());
    assertEquals(2, calls.get());
  }

  @Test
  void submissionFailureAndAnInvalidStoreResponseDoNotPoisonTheGenerator() {
    AtomicInteger calls = new AtomicInteger();
    AsyncSequenceGenerator sequence =
        new AsyncSequenceGenerator(
            "operation",
            FORMAT,
            (name, period, amount) -> {
              int call = calls.incrementAndGet();
              if (call == 1) throw new IllegalStateException("submission failed");
              if (call == 2) return CompletableFuture.completedFuture(null);
              if (call == 3) return CompletableFuture.completedFuture(0L);
              return CompletableFuture.completedFuture(1L);
            },
            1,
            CLOCK);
    for (int i = 0; i < 3; i++) {
      CompletionException error =
          assertThrows(
              CompletionException.class, () -> sequence.nextValue().toCompletableFuture().join());
      assertInstanceOf(IllegalStateException.class, error.getCause());
    }
    assertEquals(1L, sequence.nextValue().toCompletableFuture().join());
  }

  @Test
  void failedNativeCallIsSharedAndTheNextReservationCanSucceed() {
    CompletableFuture<Long> pending = new CompletableFuture<>();
    AtomicInteger calls = new AtomicInteger();
    AsyncSequenceGenerator sequence =
        new AsyncSequenceGenerator(
            "operation",
            FORMAT,
            (name, period, amount) ->
                calls.incrementAndGet() == 1 ? pending : CompletableFuture.completedFuture(1L),
            1,
            CLOCK);
    var first = sequence.nextValue().toCompletableFuture();
    var second = sequence.nextValue().toCompletableFuture();
    pending.completeExceptionally(new IllegalStateException("unavailable"));
    assertThrows(CompletionException.class, first::join);
    assertThrows(CompletionException.class, second::join);
    assertEquals(1L, sequence.nextValue().toCompletableFuture().join());
  }

  @Test
  void capturesPeriodOnceEvenIfTheClockChangesDuringIo() {
    SequenceTest.MutableClock clock = new SequenceTest.MutableClock();
    CompletableFuture<Long> first = new CompletableFuture<>();
    List<String> periods = new ArrayList<>();
    AsyncSequenceGenerator sequence =
        new AsyncSequenceGenerator(
            "operation",
            new SequenceFormat("OP-", 2, ResetPolicy.DAILY, "-", SequenceTest.LIMA),
            (name, period, amount) -> {
              periods.add(period);
              return periods.size() == 1 ? first : CompletableFuture.completedFuture(1L);
            },
            1,
            clock);
    var beforeMidnight = sequence.next().toCompletableFuture();
    clock.now = Instant.parse("2026-02-01T06:00:00Z");
    first.complete(1L);
    assertEquals("OP-20260131-01", beforeMidnight.join());
    assertEquals("OP-20260201-01", sequence.next().toCompletableFuture().join());
    assertEquals(List.of("20260131", "20260201"), periods);
  }

  @Test
  void independentGeneratorsAndConcurrentCallersNeverReuseAValue() throws Exception {
    AtomicLong counter = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();
    AsyncSequenceStore store =
        (name, period, amount) -> {
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(counter.addAndGet(amount));
        };
    AsyncSequenceGenerator first =
        new AsyncSequenceGenerator("operation", FORMAT, store, 10, CLOCK);
    AsyncSequenceGenerator second =
        new AsyncSequenceGenerator("operation", FORMAT, store, 10, CLOCK);
    Set<Long> values = ConcurrentHashMap.newKeySet();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var work =
          IntStream.range(0, 1000)
              .<Callable<Boolean>>mapToObj(
                  i ->
                      () ->
                          values.add(
                              (i % 2 == 0 ? first : second)
                                  .nextValue()
                                  .toCompletableFuture()
                                  .join()))
              .toList();
      for (var result : pool.invokeAll(work)) assertTrue(result.get());
    }
    assertEquals(1000, values.size());
    assertTrue(calls.get() <= 102, "block reservations must reduce native calls");
    assertTrue(values.stream().allMatch(value -> value > 0));
  }

  @Test
  void maximumLongIsReturnedOnceAndNeverWrapsToNegativeValues() {
    AtomicInteger calls = new AtomicInteger();
    AsyncSequenceGenerator sequence =
        new AsyncSequenceGenerator(
            "operation",
            FORMAT,
            (name, period, amount) ->
                calls.incrementAndGet() == 1
                    ? CompletableFuture.completedFuture(Long.MAX_VALUE)
                    : CompletableFuture.failedFuture(new ArithmeticException("exhausted")),
            2,
            CLOCK);
    assertEquals(Long.MAX_VALUE - 1, sequence.nextValue().toCompletableFuture().join());
    assertEquals(Long.MAX_VALUE, sequence.nextValue().toCompletableFuture().join());
    assertInstanceOf(
        ArithmeticException.class,
        assertThrows(
                CompletionException.class, () -> sequence.nextValue().toCompletableFuture().join())
            .getCause());
  }

  @Test
  void validatesNamesBlockSizesAndRenderedPeriodCapacity() {
    AsyncSequenceStore store = (name, period, amount) -> CompletableFuture.completedFuture(100L);
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncSequenceGenerator("Bad Name", FORMAT, store, 1, CLOCK));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncSequenceGenerator("ok", FORMAT, store, 0, CLOCK));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncSequenceGenerator("ok", FORMAT, store, 10001, CLOCK));
    AsyncSequenceGenerator sequence =
        new AsyncSequenceGenerator(
            "operation",
            new SequenceFormat("OP-", 2, ResetPolicy.DAILY, "-", ZoneOffset.UTC),
            store,
            1,
            CLOCK);
    assertInstanceOf(
        SequenceExhaustedException.class,
        assertThrows(CompletionException.class, () -> sequence.next().toCompletableFuture().join())
            .getCause());
  }
}
