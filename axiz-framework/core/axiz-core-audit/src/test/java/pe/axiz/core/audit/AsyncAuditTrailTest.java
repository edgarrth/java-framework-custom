package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;

class AsyncAuditTrailTest {
  @Test
  void sharesSanitizationAndImmutableSnapshotsWithImperativeTrail() {
    List<AuditEvent> sync = new ArrayList<>();
    List<AuditEvent> async = new ArrayList<>();
    Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    AuditTrail.Context context =
        new AuditTrail.Context(new AuditEvent.Actor("u", "app", "ip"), "corr", "web", "tenant");
    AuditTrail imperative =
        new AuditTrail("svc", sync::add, ObjectMasker.defaults(), () -> "same-id", clock);
    AsyncAuditTrail nativeTrail =
        new AsyncAuditTrail(
            "svc",
            event -> {
              async.add(event);
              return CompletableFuture.completedFuture(null);
            },
            ObjectMasker.defaults(),
            () -> "same-id",
            clock);
    imperative
        .record("create", "account")
        .resourceId("a")
        .context(context)
        .detail("request", Map.of("password", "secret", "amount", 10))
        .success();
    var record =
        nativeTrail
            .record("create", "account")
            .resourceId("a")
            .context(context)
            .detail("request", Map.of("password", "secret", "amount", 10));
    record.success().toCompletableFuture().join();
    assertEquals(sync, async);
    assertFalse(async.toString().contains("secret"));
    record.detail("extra", 1).failure("ERR").toCompletableFuture().join();
    assertFalse(async.getFirst().details().containsKey("extra"));
    assertEquals("ERR", async.getLast().errorCode());
    assertEquals(AuditEvent.Outcome.FAILURE, async.getLast().outcome());
    assertThrows(UnsupportedOperationException.class, () -> async.getFirst().details().put("x", 1));
  }

  @Test
  void acceptanceFailureAndCancellationBelongToTheNativeOperation() {
    CompletableFuture<Void> nativeCall = new CompletableFuture<>();
    AsyncAuditTrail trail = trail(event -> nativeCall);
    var delivery = trail.record("a", "r").success().toCompletableFuture();
    assertSame(nativeCall, delivery);
    assertFalse(delivery.isDone());
    assertTrue(delivery.cancel(true));
    assertTrue(nativeCall.isCancelled());
    IllegalStateException unavailable = new IllegalStateException("unavailable");
    var rejected =
        trail(
                event -> {
                  throw unavailable;
                })
            .record("a", "r")
            .success();
    assertSame(
        unavailable,
        assertThrows(CompletionException.class, () -> rejected.toCompletableFuture().join())
            .getCause());
  }

  @Test
  void fanoutAttemptsAllSinksReportsFailuresAndCancelsOnlyActiveDelivery() {
    List<AuditEvent> delivered = new ArrayList<>();
    IllegalStateException unavailable = new IllegalStateException("down");
    AsyncAuditEventPublisher broken =
        event -> {
          throw unavailable;
        };
    AsyncAuditEventPublisher successful =
        event -> {
          delivered.add(event);
          return CompletableFuture.completedFuture(null);
        };
    var fanout = new CompositeAsyncAuditPublisher(List.of(broken, successful));
    var failure = trail(fanout).record("a", "r").success();
    assertSame(
        unavailable,
        assertThrows(CompletionException.class, () -> failure.toCompletableFuture().join())
            .getCause());
    assertEquals(1, delivered.size());
    CompletableFuture<Void> pending = new CompletableFuture<>();
    var cancellable = new CompositeAsyncAuditPublisher(List.of(event -> pending, successful));
    assertTrue(trail(cancellable).record("a", "r").success().toCompletableFuture().cancel(true));
    assertTrue(pending.isCancelled());
    assertEquals(1, delivered.size());
    trail(new CompositeAsyncAuditPublisher(List.of()))
        .record("a", "r")
        .success()
        .toCompletableFuture()
        .join();
    trail(new CompositeAsyncAuditPublisher(List.of(successful)))
        .record("a", "r")
        .success()
        .toCompletableFuture()
        .join();
    assertEquals(2, delivered.size());
    trail(new LoggingAsyncAuditPublisher()).record("a", "r").success().toCompletableFuture().join();
  }

  private static AsyncAuditTrail trail(AsyncAuditEventPublisher publisher) {
    return new AsyncAuditTrail(
        "svc", publisher, ObjectMasker.defaults(), IdGenerator.uuidV7(), Clock.systemUTC());
  }
}
