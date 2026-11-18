package pe.axiz.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;

/** Policy tests use a controllable port, without sockets, workers or a provider SDK. */
class AsyncTokenPolicyTest {
  private final MovingClock clock = new MovingClock();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<AsyncHttpExchange.Request> sent = new AtomicReference<>();
  private final AtomicReference<CompletableFuture<String>> secret =
      new AtomicReference<>(new CompletableFuture<>());
  private final AtomicReference<CompletableFuture<AsyncHttpExchange.Response>> reply =
      new AtomicReference<>(new CompletableFuture<>());

  private ClientCredentialsAsyncTokenProvider provider() {
    return ClientCredentialsAsyncTokenProvider.builder()
        .http(
            (request, limit) -> {
              assertEquals(65_536, limit);
              sent.set(request);
              calls.incrementAndGet();
              return reply.get();
            })
        .tokenUri(URI.create("https://example.test/token"))
        .clientId("client")
        .clientSecret(secret::get)
        .scopes(List.of("read"))
        .audience(null)
        .renewBefore(Duration.ofSeconds(5))
        .timeout(Duration.ofSeconds(2))
        .clock(clock)
        .transportSecurity(TransportSecurityPolicy.secure())
        .build();
  }

  @Test
  void coalescesColdRequestsAndReusesValidTokensUntilRenewalReadsTheRotatedSecret() {
    var provider = provider();
    var pending =
        IntStream.range(0, 40).mapToObj(i -> provider.token().toCompletableFuture()).toList();
    assertEquals(0, calls.get());
    secret.get().complete("first");
    assertEquals(1, calls.get());
    var firstRequest = sent.get();
    assertEquals(Duration.ofSeconds(2), firstRequest.timeout());
    clock.now = Instant.EPOCH.plusSeconds(2);
    reply
        .get()
        .complete(
            new AsyncHttpExchange.Response(
                200, null, "{\"access_token\":\"token-one\",\"expires_in\":10}"));
    var token = pending.getFirst().join();
    assertEquals(Instant.EPOCH.plusSeconds(10), token.expiresAt());
    pending.forEach(result -> assertSame(token, result.join()));
    assertSame(token, provider.token().toCompletableFuture().join());
    clock.now = Instant.EPOCH.plusSeconds(6);
    secret.set(CompletableFuture.completedFuture("rotated"));
    reply.set(new CompletableFuture<>());
    var renewal = provider.token().toCompletableFuture();
    assertEquals(2, calls.get());
    assertFalse(firstRequest.headers().equals(sent.get().headers()));
    reply
        .get()
        .complete(new AsyncHttpExchange.Response(200, null, "{\"access_token\":\"token-two\"}"));
    assertEquals("token-two", renewal.join().value());
  }

  @Test
  void onlyTheLastWaitingCallerCancelsNativeWorkAndNextCallCanRecover() {
    var provider = provider();
    var first = provider.token().toCompletableFuture();
    var second = provider.token().toCompletableFuture();
    first.cancel(true);
    assertFalse(secret.get().isCancelled());
    second.cancel(true);
    assertTrue(secret.get().isCancelled());
    secret.set(CompletableFuture.completedFuture("rotated"));
    var exchange = provider.token().toCompletableFuture();
    assertEquals(1, calls.get());
    exchange.cancel(true);
    assertTrue(reply.get().isCancelled());
    reply.set(
        CompletableFuture.completedFuture(
            new AsyncHttpExchange.Response(200, null, "{\"access_token\":\"recovered\"}")));
    assertEquals("recovered", provider.token().toCompletableFuture().join().value());
  }

  @Test
  void failedNativeRequestsDoNotPoisonTheCache() {
    var provider = provider();
    secret.set(CompletableFuture.completedFuture("secret"));
    reply.set(CompletableFuture.failedFuture(new IllegalStateException("private cause")));
    var failure =
        assertThrows(
            CompletionException.class, () -> provider.token().toCompletableFuture().join());
    assertInstanceOf(ExternalServiceException.class, failure.getCause());
    reply.set(
        CompletableFuture.completedFuture(
            new AsyncHttpExchange.Response(200, null, "private invalid response")));
    var malformed =
        assertThrows(
            CompletionException.class, () -> provider.token().toCompletableFuture().join());
    assertInstanceOf(IllegalStateException.class, malformed.getCause());
    assertFalse(malformed.toString().contains("private"));
    reply.set(
        CompletableFuture.completedFuture(
            new AsyncHttpExchange.Response(200, null, "{\"access_token\":\"ok\"}")));
    assertEquals("ok", provider.token().toCompletableFuture().join().value());
  }

  private static final class MovingClock extends Clock {
    private Instant now = Instant.EPOCH;

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
