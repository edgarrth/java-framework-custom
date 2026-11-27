package pe.axiz.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnedStagesTest {
  @Test
  void releasesBeforeDeliveryAndPreservesTheIndependentOutput() {
    byte[] input = {1, 2};
    var result =
        Stages.consume(
            CompletableFuture.completedFuture(input),
            byte[]::clone,
            value -> Arrays.fill(value, (byte) 0),
            value -> Arrays.fill(value, (byte) 0));
    assertArrayEquals(new byte[2], input);
    assertArrayEquals(new byte[] {1, 2}, result.toCompletableFuture().join());
  }

  @Test
  void aLateValueIsReleasedWithoutRunningTheMapper() {
    var source =
        new CompletableFuture<byte[]>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    var result =
        Stages.consume(
                source,
                value -> {
                  throw new AssertionError("Cancelled mapper ran");
                },
                value -> Arrays.fill(value, (byte) 0),
                ignored -> {})
            .toCompletableFuture();
    assertTrue(result.cancel(true));
    byte[] value = {1};
    source.complete(value);
    assertArrayEquals(new byte[1], value);
  }

  @Test
  void cancellationDuringConversionDiscardsBothInputAndUndeliveredOutput() throws Exception {
    var source = new CompletableFuture<byte[]>();
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var computed = new AtomicReference<byte[]>();
    var result =
        Stages.consume(
                source,
                value -> {
                  byte[] output = value.clone();
                  computed.set(output);
                  entered.countDown();
                  try {
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                  }
                  return output;
                },
                value -> Arrays.fill(value, (byte) 0),
                value -> Arrays.fill(value, (byte) 0))
            .toCompletableFuture();
    byte[] input = {1};
    try (var worker = Executors.newSingleThreadExecutor()) {
      var completion = worker.submit(() -> source.complete(input));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      result.cancel(true);
      finish.countDown();
      completion.get(5, TimeUnit.SECONDS);
      assertArrayEquals(new byte[1], input);
      assertArrayEquals(new byte[1], computed.get());
    }
  }

  @Test
  void cleanupRunsOnConversionFailureAndDoesNotDependOnContextAccessors() {
    byte[] input = {1};
    var failure = new IllegalStateException("conversion failed");
    try (var scope =
        Stages.context(
            () -> {
              throw new IllegalStateException("context unavailable");
            })) {
      var result =
          Stages.consume(
                  CompletableFuture.completedFuture(input),
                  value -> {
                    throw failure;
                  },
                  value -> Arrays.fill(value, (byte) 0),
                  ignored -> {})
              .toCompletableFuture();
      assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
      assertEquals(0, input[0]);
    }
  }
}
