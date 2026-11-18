package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pe.axiz.core.commons.TransactionOutcome;

@Timeout(10)
class AuditShutdownTest {
  private AuditEvent event() {
    return AuditEvent.builder()
        .id("e")
        .occurredAt(Instant.EPOCH)
        .service("service")
        .action("operation")
        .resourceType("account")
        .resourceId(null)
        .actor(new AuditEvent.Actor(null, null, null))
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void cancellationAndFallbackFinishBeforeTheSnapshotIsDeclaredFinal() throws Exception {
    var entered = new CountDownLatch(1);
    var never = new CountDownLatch(1);
    var publisher =
        new AsyncAuditPublisher(
            e -> {
              entered.countDown();
              try {
                never.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cancelled");
              }
            },
            e -> {
              throw new IllegalStateException("fallback failed");
            },
            2,
            Duration.ZERO,
            Duration.ofMillis(500),
            Duration.ofSeconds(10),
            () -> () -> () -> {});
    publisher.publish(event());
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    publisher.publish(event());
    var result = publisher.shutdown();
    assertFalse(result.drainedInTime());
    assertTrue(result.terminated());
    assertFalse(publisher.workerAlive());
    assertEquals(2, result.lost());
    assertEquals(1, result.flushedToFallback());
    assertEquals(result.lost(), publisher.lostCount());
    assertSame(result, publisher.shutdown());
    assertThrows(IllegalStateException.class, () -> publisher.publish(event()));
  }

  @Test
  void uncooperativeFallbackCannotHoldShutdownOrPretendToBeConfirmedLoss() throws Exception {
    var fallbackEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var publisher =
        new AsyncAuditPublisher(
            e -> {
              throw new IllegalStateException("broker");
            },
            e -> {
              fallbackEntered.countDown();
              while (release.getCount() != 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                  /* deliberately uncooperative test callback */
                }
              }
              throw new IllegalStateException("late failure");
            },
            2,
            Duration.ZERO,
            Duration.ofMillis(300),
            Duration.ofSeconds(10),
            () -> () -> () -> {});
    try {
      publisher.publish(event());
      assertTrue(fallbackEntered.await(2, TimeUnit.SECONDS));
      long started = System.nanoTime();
      var result = publisher.shutdown();
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
      assertFalse(result.terminated());
      assertEquals(0, result.lost());
      assertEquals(1, result.unconfirmed());
      release.countDown();
      assertEquals(
          0,
          publisher.lostCount(),
          "Late callback failure must not mutate confirmed-loss accounting");
    } finally {
      release.countDown();
      publisher.close();
    }
  }

  @Test
  void nativeOverflowIsBoundedAndCancellationCannotTurnIntoALateConfirmedLoss() throws Exception {
    var entered = new CountDownLatch(1);
    var delivery = new CountDownLatch(1);
    var fallbackEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var fallbackReturned = new CountDownLatch(1);
    var publisher =
        new AsyncAuditPublisher(
            e -> {
              entered.countDown();
              try {
                delivery.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            },
            e -> {
              fallbackEntered.countDown();
              try {
                while (release.getCount() != 0) {
                  try {
                    release.await();
                  } catch (InterruptedException ignored) {
                    /* deliberately uncooperative */
                  }
                }
                throw new IllegalStateException("late fallback failure");
              } finally {
                fallbackReturned.countDown();
              }
            },
            1,
            Duration.ZERO,
            Duration.ofMillis(300),
            Duration.ofSeconds(10),
            () -> () -> () -> {});
    try {
      publisher.publishWithoutWait(event());
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      publisher.publishWithoutWait(event());
      publisher.publishWithoutWait(event());
      assertTrue(fallbackEntered.await(2, TimeUnit.SECONDS));
      publisher.publishWithoutWait(event());
      publisher.publishWithoutWait(event());
      assertEquals(
          1,
          publisher.lostCount(),
          "Exhausted fallback admission rejects without creating more workers");
      publisher.publish(event());
      assertEquals(
          2,
          publisher.lostCount(),
          "Imperative fallback rejected before execution is also a confirmed loss");
      var result = publisher.shutdown();
      assertFalse(result.terminated());
      assertTrue(result.unconfirmed() >= 1);
      long losses = publisher.lostCount();
      release.countDown();
      assertTrue(fallbackReturned.await(2, TimeUnit.SECONDS));
      assertEquals(losses, publisher.lostCount());
    } finally {
      release.countDown();
      delivery.countDown();
      publisher.close();
    }
  }
}
