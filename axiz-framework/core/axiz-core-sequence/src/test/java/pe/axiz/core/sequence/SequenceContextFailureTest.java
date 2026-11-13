package pe.axiz.core.sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.axiz.core.commons.Stages;

class SequenceContextFailureTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void aCaptureFailureCompletesTheCallerAndDoesNotReserveARange(int failingCapture)
      throws Exception {
    var calls = new AtomicInteger();
    var captures = new AtomicInteger();
    var failure = new IllegalStateException("context unavailable");
    var generator =
        new AsyncSequenceGenerator(
            "orders",
            new SequenceFormat("OP-", 4, ResetPolicy.NEVER, "-", ZoneOffset.UTC),
            (name, period, block) -> {
              calls.incrementAndGet();
              return CompletableFuture.completedFuture((long) block);
            },
            4,
            Clock.systemUTC());
    try (var scope =
        Stages.context(
            () -> {
              if (captures.incrementAndGet() == failingCapture) throw failure;
              return () -> () -> {};
            })) {
      var result = generator.nextValue().toCompletableFuture();
      var observed = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
      assertSame(failure, observed.getCause());
    }
    assertEquals(0, calls.get());
    assertEquals(1L, generator.nextValue().toCompletableFuture().get(2, TimeUnit.SECONDS));
    assertEquals(1, calls.get());
  }
}
