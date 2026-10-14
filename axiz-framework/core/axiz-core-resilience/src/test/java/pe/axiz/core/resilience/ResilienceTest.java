package pe.axiz.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;

class ResilienceTest {

  @Test
  void backoffGrowsAndIsCapped() {
    Backoff b = new Backoff(Duration.ofMillis(100), 2.0, Duration.ofMillis(500), false);
    assertEquals(Duration.ofMillis(100), b.delay(1));
    assertEquals(Duration.ofMillis(200), b.delay(2));
    assertEquals(Duration.ofMillis(500), b.delay(5));
    Backoff jittered = Backoff.exponential(Duration.ofMillis(100), Duration.ofSeconds(1));
    for (int i = 1; i < 10; i++) {
      long d = jittered.delay(i).toMillis();
      assertTrue(d >= 0 && d <= 1000);
    }
    assertEquals(Duration.ZERO, Backoff.fixed(Duration.ZERO).delay(3));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Backoff(Duration.ofSeconds(2), 2, Duration.ofSeconds(1), true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Backoff(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(1), true));
  }

  @Test
  void onlyIdempotentTransientFailuresAreRetried() {
    assertTrue(RetryDecider.shouldRetry("get", null, new SocketTimeoutException()));
    assertFalse(RetryDecider.shouldRetry("POST", null, new SocketTimeoutException()));
    assertTrue(RetryDecider.shouldRetry("POST", "key-123", new SocketTimeoutException()));
    assertFalse(
        RetryDecider.shouldRetry("GET", null, new BusinessException(CoreErrors.CONFLICT, "x")));
    assertTrue(
        RetryDecider.isTransient(
            ExternalServiceException.from(new ExternalErrorDetail("s", 503, null, null, ""))));
    assertFalse(
        RetryDecider.isTransient(
            ExternalServiceException.from(new ExternalErrorDetail("s", 400, null, null, ""))));
    assertTrue(RetryDecider.isTransient(new RuntimeException(new IOException("reset"))));
    assertFalse(RetryDecider.isTransient(new IllegalStateException()));
  }

  @Test
  void retryRunsUntilSuccessOrExhaustion() throws Exception {
    List<Duration> sleeps = new ArrayList<>();
    List<Integer> retries = new ArrayList<>();
    Retry retry =
        new Retry(
            RetryPolicy.transientFailures(3, Backoff.fixed(Duration.ofMillis(10))),
            sleeps::add,
            (a, f, d) -> retries.add(a));
    AtomicInteger calls = new AtomicInteger();
    assertEquals(
        "ok",
        retry.call(
            () -> {
              if (calls.incrementAndGet() < 3) {
                throw new SocketTimeoutException();
              }
              return "ok";
            }));
    assertEquals(List.of(1, 2), retries);
    assertEquals(2, sleeps.size());
    AtomicInteger calls2 = new AtomicInteger();
    assertThrows(
        SocketTimeoutException.class,
        () ->
            retry.call(
                () -> {
                  calls2.incrementAndGet();
                  throw new SocketTimeoutException();
                }));
    assertEquals(3, calls2.get());
    AtomicInteger calls3 = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            retry.call(
                () -> {
                  calls3.incrementAndGet();
                  throw new IllegalStateException();
                }));
    assertEquals(1, calls3.get());
    assertEquals(1, new Retry(RetryPolicy.none()).call(() -> 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryPolicy(0, Backoff.fixed(Duration.ZERO), t -> true));
  }

  @Test
  void interruptedSleepStopsRetrying() {
    Retry retry =
        new Retry(
            RetryPolicy.transientFailures(3, Backoff.fixed(Duration.ofMillis(1))),
            d -> {
              throw new InterruptedException();
            },
            Retry.Listener.none());
    SocketTimeoutException e =
        assertThrows(
            SocketTimeoutException.class,
            () ->
                retry.call(
                    () -> {
                      throw new SocketTimeoutException();
                    }));
    assertEquals(1, e.getSuppressed().length);
    assertTrue(Thread.interrupted());
  }

  @Test
  void timeoutsAreMandatory() {
    assertEquals(Duration.ofSeconds(5), TimeoutSettings.defaults().read());
    assertThrows(
        NullPointerException.class,
        () -> new TimeoutSettings(null, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeoutSettings(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TimeoutSettings(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(1)));
  }
}
