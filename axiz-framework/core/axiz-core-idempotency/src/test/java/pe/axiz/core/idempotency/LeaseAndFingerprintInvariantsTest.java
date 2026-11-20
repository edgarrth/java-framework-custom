package pe.axiz.core.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeaseAndFingerprintInvariantsTest {
  @Test
  void everyRequestAndCallerComponentParticipatesInTheFingerprint() {
    byte[] body = "amount=10".getBytes(StandardCharsets.UTF_8);
    assertNotEquals(
        IdempotencyService.fingerprint("COMMAND", "/payments", "v=1", body),
        IdempotencyService.fingerprint("COMMAND/", "payments", "v=1", body));
    assertNotEquals(
        IdempotencyService.fingerprint("POST", "/payments", "v=1", body),
        IdempotencyService.fingerprint(
            "POST", "/payments", "v=1amount=", "10".getBytes(StandardCharsets.UTF_8)));
    String expected = IdempotencyService.fingerprint("POST", "/payments", "v=1", body);
    assertEquals(
        expected, IdempotencyService.fingerprint("POST", "/payments", "v=1", body.clone()));
    assertNotEquals(expected, IdempotencyService.fingerprint("PUT", "/payments", "v=1", body));
    assertNotEquals(expected, IdempotencyService.fingerprint("POST", "/orders", "v=1", body));
    assertNotEquals(expected, IdempotencyService.fingerprint("POST", "/payments", "v=2", body));
    assertNotEquals(
        expected, IdempotencyService.fingerprint("POST", "/payments", "v=1", new byte[0]));
    assertNotEquals(
        IdempotencyService.fingerprint("POST", "/ab", "c", body),
        IdempotencyService.fingerprint("POST", "/a", "bc", body));
    String scope = IdempotencyService.scope("pay", "issuer", "tenant", "client", "subject");
    assertNotEquals(scope, IdempotencyService.scope("pay", "other", "tenant", "client", "subject"));
    assertNotEquals(scope, IdempotencyService.scope("pay", "issuer", "other", "client", "subject"));
    assertNotEquals(scope, IdempotencyService.scope("pay", "issuer", "tenant", "other", "subject"));
    assertNotEquals(scope, IdempotencyService.scope("pay", "issuer", "tenant", "client", "other"));
    assertNotEquals(
        IdempotencyService.scope("pay", null, "tenant", "client", "subject"),
        IdempotencyService.scope("pay", "", "tenant", "client", "subject"));
  }

  @Test
  void aLateRenewalFailureAfterCloseDoesNotTurnACompletedOperationIntoUnknown() throws Exception {
    var entered = new CountDownLatch(1);
    var renewal = new CompletableFuture<Boolean>();
    var uncertain = new AtomicInteger();
    try (var lease =
        IdempotencyLease.start(
            Duration.ofMillis(1),
            Duration.ofSeconds(5),
            () -> {
              entered.countDown();
              return renewal;
            },
            () -> CompletableFuture.completedFuture(uncertain.incrementAndGet() > 0))) {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      lease.close();
      renewal.complete(false);
      assertEquals(0, uncertain.get());
    }
  }

  @Test
  void aLostLeaseAndASynchronousDriverFailureAreReconciledExactlyOnce() throws Exception {
    for (boolean synchronousFailure : new boolean[] {false, true}) {
      var entered = new CountDownLatch(1);
      var recorded = new CountDownLatch(1);
      var renewal = new CompletableFuture<Boolean>();
      var uncertain = new AtomicInteger();
      try (var lease =
          IdempotencyLease.start(
              Duration.ofMillis(1),
              Duration.ofSeconds(5),
              () -> {
                entered.countDown();
                if (synchronousFailure) throw new IllegalStateException("driver unavailable");
                return renewal;
              },
              () -> {
                uncertain.incrementAndGet();
                recorded.countDown();
                return CompletableFuture.completedFuture(true);
              })) {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        renewal.complete(false);
        assertTrue(recorded.await(5, TimeUnit.SECONDS));
        assertEquals(1, uncertain.get());
      }
    }
  }
}
