package pe.axiz.core.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class LeaseDeadlineTest {
  @Test
  void serviceLeaseMinimumIsValidatedBeforeAnyReservationOnBothApis() {
    var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    var syncStore = new InMemoryIdempotencyStore(clock);
    var asyncStore = new InMemoryAsyncIdempotencyStore(clock);
    for (Duration ttl :
        List.of(
            Duration.ofNanos(-1),
            Duration.ZERO,
            Duration.ofNanos(1),
            Duration.ofNanos(2_999_999))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new IdempotencyService(syncStore, ttl, Duration.ofHours(1), clock));
      assertThrows(
          IllegalArgumentException.class,
          () -> new AsyncIdempotencyService(asyncStore, ttl, Duration.ofHours(1), clock));
    }
    var sync = new IdempotencyService(syncStore, Duration.ofMillis(3), Duration.ofHours(1), clock);
    var async =
        new AsyncIdempotencyService(asyncStore, Duration.ofMillis(3), Duration.ofHours(1), clock);
    String key = "minimum-lease-key-0001";
    var first = (IdempotencyService.Proceed) sync.begin("scope", key, "fp");
    var second =
        (IdempotencyService.Proceed) async.begin("scope", key, "fp").toCompletableFuture().join();
    try (var syncLease = sync.startLease(first);
        var asyncLease = async.startLease(second)) {
      // La construcción en el mínimo exacto no debe rechazar una ejecución ya reservada. La
      // temporización de la renovación se prueba por separado con plazos realistas.
    }
  }

  @Test
  void pendingNativeRenewalExpiresOnceAndLateSuccessCannotReviveTheLease() throws Exception {
    var pending =
        new CompletableFuture<Boolean>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    var calls = new AtomicInteger();
    var uncertain = new CountDownLatch(1);
    try (var lease =
        IdempotencyLease.start(
            Duration.ofMillis(5),
            Duration.ofMillis(40),
            () -> pending,
            () -> {
              calls.incrementAndGet();
              uncertain.countDown();
              return CompletableFuture.completedFuture(true);
            })) {
      assertTrue(uncertain.await(5, TimeUnit.SECONDS));
      pending.complete(true);
      assertEquals(1, calls.get());
    }
  }

  @Test
  void evenBlockedSubmissionHasADeadlineAndIsInterrupted() throws Exception {
    var interrupted = new CountDownLatch(1);
    var uncertain = new CountDownLatch(1);
    try (var lease =
        IdempotencyLease.start(
            Duration.ofMillis(5),
            Duration.ofMillis(100),
            () -> {
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return CompletableFuture.completedFuture(true);
            },
            () -> {
              uncertain.countDown();
              return CompletableFuture.completedFuture(true);
            })) {
      assertTrue(uncertain.await(5, TimeUnit.SECONDS));
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void reconciliationLogsNeverRetainProviderExceptionsOrSensitiveValues() throws Exception {
    List<LogRecord> records = new CopyOnWriteArrayList<>();
    var logged = new CountDownLatch(1);
    var logger = Logger.getLogger(IdempotencyLease.class.getName());
    Handler handler =
        new Handler() {
          public void publish(LogRecord record) {
            records.add(record);
            logged.countDown();
          }

          public void flush() {}

          public void close() {}
        };
    logger.addHandler(handler);
    var failure =
        new IllegalStateException(
            "PAN-4111111111111111", new IllegalArgumentException("DNI-12345678"));
    failure.addSuppressed(new IllegalStateException("APIKEY-CANARY"));
    try (var lease =
        IdempotencyLease.start(
            Duration.ofMillis(5),
            () -> CompletableFuture.completedFuture(false),
            () -> CompletableFuture.failedFuture(failure))) {
      assertTrue(logged.await(5, TimeUnit.SECONDS));
      for (var record : records) {
        assertNull(record.getThrown());
        assertNull(record.getParameters());
        assertFalse(record.getMessage().contains("4111111111111111"));
        assertFalse(record.getMessage().contains("12345678"));
        assertFalse(record.getMessage().contains("APIKEY"));
      }
    } finally {
      logger.removeHandler(handler);
    }
  }

  @Test
  void blockedUncertaintySubmissionAlsoHasADeadline() throws Exception {
    var interrupted = new CountDownLatch(1);
    try (var lease =
        IdempotencyLease.start(
            Duration.ofMillis(5),
            Duration.ofMillis(100),
            () -> CompletableFuture.completedFuture(false),
            () -> {
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException failure) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return CompletableFuture.completedFuture(false);
            })) {
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void invalidDeadlinesAreRejectedBeforeStartingAWorker() {
    for (Duration interval : java.util.List.of(Duration.ofNanos(1), Duration.ofNanos(999999))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              IdempotencyLease.start(
                  interval,
                  () -> CompletableFuture.completedFuture(true),
                  () -> CompletableFuture.completedFuture(true)));
    }
    for (Duration deadline : java.util.List.of(Duration.ZERO, Duration.ofNanos(-1))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              IdempotencyLease.start(
                  Duration.ofMillis(1),
                  deadline,
                  () -> CompletableFuture.completedFuture(true),
                  () -> CompletableFuture.completedFuture(true)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IdempotencyLease.start(
                Duration.ZERO,
                Duration.ZERO,
                () -> CompletableFuture.completedFuture(true),
                () -> CompletableFuture.completedFuture(true)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IdempotencyLease.start(
                Duration.ofMillis(-1),
                () -> CompletableFuture.completedFuture(true),
                () -> CompletableFuture.completedFuture(true)));
  }
}
