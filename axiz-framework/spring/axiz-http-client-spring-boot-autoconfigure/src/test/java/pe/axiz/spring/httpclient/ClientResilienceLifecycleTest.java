package pe.axiz.spring.httpclient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.resilience.TimeoutSettings;
import reactor.core.publisher.Mono;

class ClientResilienceLifecycleTest {
  private static final URI URI_BANK = URI.create("https://bank.invalid");

  private static ClientSettings settings(boolean circuit) {
    return new ClientSettings(
        "bank",
        URI_BANK,
        new TimeoutSettings(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2)),
        AuthSettings.none(),
        ClientSettings.RetryMode.NONE,
        1,
        circuit ? "default" : null,
        1,
        List.of(),
        10,
        TransportSecurityPolicy.system());
  }

  private static AxizRestClientInterceptor interceptor(ClientResilience r) {
    return new AxizRestClientInterceptor(
        r,
        OutboundAuth.create(r.settings(), null, Duration.ofSeconds(30), Clock.systemUTC()),
        null);
  }

  private static MockClientHttpRequest request() {
    return new MockClientHttpRequest(HttpMethod.GET, URI_BANK);
  }

  private static void capacityIsExactlyOne(ClientResilience r) throws Exception {
    var response =
        interceptor(r)
            .intercept(
                request(),
                new byte[0],
                (a, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));
    response.close();
    r.acquire();
    try {
      assertThrows(ExternalServiceException.class, r::acquire);
    } finally {
      r.release(System.nanoTime(), null, 200);
    }
  }

  @Test
  void uncheckedAndErrorFailuresLeaveExactlyOneAvailablePermit() throws Exception {
    for (Throwable failure :
        List.of(new IllegalStateException("closed"), new AssertionError("synthetic"))) {
      var r = new ClientResilience(settings(false), CircuitBreakerRegistry.ofDefaults());
      var n = new AtomicInteger();
      Throwable actual =
          assertThrows(
              failure.getClass(),
              () ->
                  interceptor(r)
                      .intercept(
                          request(),
                          new byte[0],
                          (a, b) -> {
                            n.incrementAndGet();
                            if (failure instanceof Error e) throw e;
                            throw (RuntimeException) failure;
                          }));
      assertSame(failure, actual);
      assertEquals(1, n.get());
      capacityIsExactlyOne(r);
    }
  }

  @Test
  void statusReadFailureClosesResponseAndReleasesItsPermit() throws Exception {
    for (Throwable failure :
        List.of(
            new IOException("bad status"),
            new IllegalStateException("bad status"),
            new AssertionError("bad status"))) {
      var r = new ClientResilience(settings(false), CircuitBreakerRegistry.ofDefaults());
      var response = mock(ClientHttpResponse.class);
      when(response.getStatusCode()).thenThrow(failure);
      assertThrows(
          failure.getClass(),
          () -> interceptor(r).intercept(request(), new byte[0], (a, b) -> response));
      verify(response, times(1)).close();
      capacityIsExactlyOne(r);
    }
  }

  @Test
  void responseCleanupDoesNotReplaceTheOriginalFailure() throws Exception {
    var primary = new IllegalStateException("status");
    var cleanup = new IllegalArgumentException("close");
    var r = new ClientResilience(settings(false), CircuitBreakerRegistry.ofDefaults());
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenThrow(primary);
    doThrow(cleanup).when(response).close();
    assertSame(
        primary,
        assertThrows(
            IllegalStateException.class,
            () -> interceptor(r).intercept(request(), new byte[0], (a, b) -> response)));
    assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
    verify(response, times(1)).close();
    capacityIsExactlyOne(r);
  }

  @Test
  void halfOpenCallTerminatesAndTheNextTrialCanCloseTheCircuit() throws Exception {
    var registry =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom().permittedNumberOfCallsInHalfOpenState(1).build());
    var r = new ClientResilience(settings(true), registry);
    var circuit = registry.circuitBreaker("http.bank");
    circuit.transitionToOpenState();
    circuit.transitionToHalfOpenState();
    assertThrows(
        IllegalStateException.class,
        () ->
            interceptor(r)
                .intercept(
                    request(),
                    new byte[0],
                    (a, b) -> {
                      throw new IllegalStateException("closed");
                    }));
    assertEquals(CircuitBreaker.State.OPEN, circuit.getState());
    circuit.transitionToHalfOpenState();
    capacityIsExactlyOne(r);
    assertEquals(CircuitBreaker.State.CLOSED, circuit.getState());
  }

  @Test
  void bulkheadIsReleasedEvenWhenCircuitNotificationFails() throws Exception {
    var registry = mock(CircuitBreakerRegistry.class);
    var circuit = mock(CircuitBreaker.class);
    when(registry.getConfiguration(anyString())).thenReturn(Optional.empty());
    when(registry.circuitBreaker(anyString())).thenReturn(circuit);
    when(circuit.tryAcquirePermission()).thenReturn(true);
    var primary = new IllegalStateException("transport");
    var reporting = new IllegalArgumentException("observer");
    doThrow(reporting).when(circuit).onError(anyLong(), eq(TimeUnit.NANOSECONDS), same(primary));
    var r = new ClientResilience(settings(true), registry);
    assertSame(
        primary,
        assertThrows(
            IllegalStateException.class,
            () ->
                interceptor(r)
                    .intercept(
                        request(),
                        new byte[0],
                        (a, b) -> {
                          throw primary;
                        })));
    assertArrayEquals(new Throwable[] {reporting}, primary.getSuppressed());
    capacityIsExactlyOne(r);
    verify(circuit, times(1)).onError(anyLong(), eq(TimeUnit.NANOSECONDS), same(primary));
  }

  @Test
  void reactiveStatusFailureReleasesBodyAndPermitWithoutMaskingOriginal() throws Exception {
    var r = new ClientResilience(settings(false), CircuitBreakerRegistry.ofDefaults());
    var filter =
        new AxizExchangeFilter(
            r,
            OutboundAuth.create(r.settings(), null, Duration.ofSeconds(30), Clock.systemUTC()),
            null);
    var request = ClientRequest.create(HttpMethod.GET, URI_BANK).build();
    var response = mock(ClientResponse.class);
    var primary = new IllegalStateException("status");
    var cleanup = new IllegalArgumentException("release");
    when(response.statusCode()).thenThrow(primary);
    when(response.releaseBody())
        .thenReturn(
            Mono.defer(
                () -> {
                  // Permit termination precedes asynchronous draining of the abandoned response.
                  r.acquire();
                  r.release(System.nanoTime(), null, 200);
                  return Mono.error(cleanup);
                }));
    assertSame(
        primary,
        assertThrows(
                ExternalServiceException.class,
                () ->
                    filter
                        .filter(request, ignored -> Mono.just(response))
                        .block(Duration.ofSeconds(3)))
            .getCause());
    assertSame(cleanup, primary.getSuppressed()[0]);
    verify(response, times(1)).releaseBody();
    capacityIsExactlyOne(r);
  }

  @Test
  void reactiveThrownAndSignalledErrorsAlsoReleaseOnlyOnce() throws Exception {
    for (boolean thrown : new boolean[] {true, false})
      for (Throwable failure :
          List.of(new IllegalStateException("transport"), new AssertionError("synthetic"))) {
        var r = new ClientResilience(settings(false), CircuitBreakerRegistry.ofDefaults());
        var filter =
            new AxizExchangeFilter(
                r,
                OutboundAuth.create(r.settings(), null, Duration.ofSeconds(30), Clock.systemUTC()),
                null);
        var request = ClientRequest.create(HttpMethod.GET, URI_BANK).build();
        assertThrows(
            Throwable.class,
            () ->
                filter
                    .filter(
                        request,
                        req -> {
                          if (!thrown) return Mono.error(failure);
                          if (failure instanceof Error e) throw e;
                          throw (RuntimeException) failure;
                        })
                    .block(Duration.ofSeconds(3)));
        assertEquals(
            HttpStatus.OK,
            filter
                .filter(request, req -> Mono.just(ClientResponse.create(HttpStatus.OK).build()))
                .block(Duration.ofSeconds(3))
                .statusCode());
        capacityIsExactlyOne(r);
      }
  }
}
