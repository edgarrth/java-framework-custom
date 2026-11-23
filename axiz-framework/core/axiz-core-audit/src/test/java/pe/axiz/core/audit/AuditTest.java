package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.sensitive.ObjectMasker;

class AuditTest {

  private final List<AuditEvent> published = new CopyOnWriteArrayList<>();
  private final AuditTrail trail =
      new AuditTrail(
          "orders-service",
          published::add,
          ObjectMasker.defaults(),
          IdGenerator.uuidV7(),
          Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void recordsSanitizedEvents() {
    AuditTrail.Context ctx =
        new AuditTrail.Context(
            new AuditEvent.Actor("user-1", "app", "10.0.0.1"), "c-1", "web", null);
    trail
        .record("order.create", "order")
        .resourceId("o-1")
        .context(ctx)
        .detail("request", Map.of("amount", 10, "password", "x", "email", "ana@example.com"))
        .success();
    AuditEvent e = published.get(0);
    assertEquals("order.create", e.action());
    assertEquals(AuditEvent.Outcome.SUCCESS, e.outcome());
    assertEquals("user-1", e.actor().subject());
    @SuppressWarnings("unchecked")
    Map<String, Object> request = (Map<String, Object>) e.details().get("request");
    assertEquals("****", request.get("password"));
    assertEquals("****@example.com", request.get("email"));
    assertEquals(10, request.get("amount"));
    String json = JsonWriter.write(e.toMap());
    assertTrue(json.contains("\"service\":\"orders-service\""));
    assertFalse(json.contains("ana@"));
  }

  @Test
  void aroundAppliesPolicy() {
    assertEquals(
        "ok", trail.record("a", "r").around(AuditPolicy.ON_FAILURE, () -> "ok", ex -> null));
    assertTrue(published.isEmpty());
    assertThrows(
        IllegalStateException.class,
        () ->
            trail
                .record("a", "r")
                .around(
                    AuditPolicy.ALWAYS,
                    () -> {
                      throw new IllegalStateException("x");
                    },
                    ex -> "AXZ-CORE-0500"));
    assertEquals(AuditEvent.Outcome.FAILURE, published.get(0).outcome());
    assertEquals("AXZ-CORE-0500", published.get(0).errorCode());
    trail.record("a", "r").around(AuditPolicy.ON_SUCCESS, () -> 1, ex -> null);
    assertEquals(2, published.size());
    assertTrue(AuditPolicy.ON_SUCCESS.applies(AuditEvent.Outcome.SUCCESS));
    assertFalse(AuditPolicy.ON_SUCCESS.applies(AuditEvent.Outcome.FAILURE));
  }

  @Test
  void asyncPublisherDeliversAndFallsBack() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    List<AuditEvent> delivered = new CopyOnWriteArrayList<>();
    List<AuditEvent> fallback = new CopyOnWriteArrayList<>();
    AuditPublisher slow =
        e -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          delivered.add(e);
        };
    try (AsyncAuditPublisher async =
        new AsyncAuditPublisher(
            slow,
            fallback::add,
            1,
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            () -> () -> () -> {})) {
      AuditTrail t =
          new AuditTrail(
              "svc", async, ObjectMasker.defaults(), IdGenerator.ulid(), Clock.systemUTC());
      for (int i = 0; i < 5; i++) {
        t.record("x", "y").success();
      }
      assertTrue(async.fallbackCount() >= 1);
      release.countDown();
    }
    assertEquals(5, delivered.size() + fallback.size());
  }

  @Test
  void deliveryFailuresGoToFallback() {
    List<AuditEvent> fallback = new CopyOnWriteArrayList<>();
    try (AsyncAuditPublisher async =
        new AsyncAuditPublisher(
            e -> {
              throw new IllegalStateException("broker down");
            },
            fallback::add,
            10,
            Duration.ofMillis(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            () -> () -> () -> {})) {
      new AuditTrail("svc", async, ObjectMasker.defaults(), IdGenerator.ulid(), Clock.systemUTC())
          .record("x", "y")
          .success();
      async.close();
      assertEquals(1, async.failureCount());
      assertThrows(IllegalStateException.class, () -> async.publish(sample()));
    }
    assertEquals(1, fallback.size());
  }

  /** Regresión: un fallback que fallaba mataba el worker y se perdía cada evento posterior. */
  @Test
  void aFailingFallbackDoesNotKillTheWorker() throws Exception {
    List<AuditEvent> delivered = new CopyOnWriteArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    AuditPublisher flaky =
        e -> {
          if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("broker down");
          }
          delivered.add(e);
        };
    AuditPublisher brokenFallback =
        e -> {
          throw new IllegalStateException("log appender down");
        };
    try (AsyncAuditPublisher async =
        new AsyncAuditPublisher(
            flaky,
            brokenFallback,
            10,
            Duration.ofMillis(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            () -> () -> () -> {})) {
      async.publish(sample());
      assertTrue(async.workerAlive(), "the worker survives a failing fallback");
      async.publish(sample());
      AsyncAuditPublisher.Shutdown shutdown = async.shutdown();
      assertTrue(shutdown.drainedInTime());
      assertEquals(1, shutdown.lost());
      assertEquals(1, async.lostCount());
    }
    assertEquals(1, delivered.size(), "events after the failure are still delivered");
  }

  @Test
  void closeFlushesWhatTheWorkerCouldNotDeliverAndReportsIt() {
    CountDownLatch never = new CountDownLatch(1);
    List<AuditEvent> fallback = new CopyOnWriteArrayList<>();
    AuditPublisher stuck =
        e -> {
          try {
            never.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
          }
        };
    AsyncAuditPublisher async =
        new AsyncAuditPublisher(
            stuck,
            fallback::add,
            10,
            Duration.ofMillis(10),
            Duration.ofMillis(200),
            Duration.ofSeconds(10),
            () -> () -> () -> {});
    for (int i = 0; i < 3; i++) {
      async.publish(sample());
    }
    AsyncAuditPublisher.Shutdown shutdown = async.shutdown();
    assertFalse(shutdown.drainedInTime());
    assertTrue(shutdown.flushedToFallback() >= 2, "queued events go to the fallback");
    assertEquals(0, shutdown.lost());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (fallback.size() < 3 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(3, fallback.size(), "the interrupted in-flight event also reaches the fallback");
  }

  @Test
  void compositeIsolatesFailures() {
    List<AuditEvent> ok = new CopyOnWriteArrayList<>();
    new CompositeAuditPublisher(
            List.of(
                e -> {
                  throw new IllegalStateException();
                },
                ok::add))
        .publish(sample());
    assertEquals(1, ok.size());
    assertThrows(
        IllegalStateException.class,
        () ->
            new CompositeAuditPublisher(
                    List.of(
                        e -> {
                          throw new IllegalStateException();
                        }))
                .publish(sample()));
    new LoggingAuditPublisher().publish(sample());
  }

  private static AuditEvent sample() {
    return AuditEvent.builder()
        .id("1")
        .occurredAt(Instant.EPOCH)
        .service("s")
        .action("a")
        .resourceType("r")
        .resourceId(null)
        .actor(AuditEvent.Actor.system())
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
}
