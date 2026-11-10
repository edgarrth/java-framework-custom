package pe.axiz.spring.httpclient;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.http.ExternalErrorTranslator;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Reactive counterpart of {@link AxizRestClientInterceptor}, without blocking: credentials that
 * need I/O use native asynchronous ports, errors are translated from the response body and retries
 * use Reactor's backoff for idempotent calls only.
 */
public class AxizExchangeFilter implements ExchangeFilterFunction {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.http.client");

  private final ClientResilience resilience;
  private final OutboundAuth auth;
  private final @Nullable String tenantHeader;

  public AxizExchangeFilter(
      ClientResilience resilience, OutboundAuth auth, @Nullable String tenantHeader) {
    this.resilience = Objects.requireNonNull(resilience);
    this.auth = Objects.requireNonNull(auth);
    this.tenantHeader = tenantHeader;
  }

  @Override
  public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
    ClientSettings settings = resilience.settings();
    // token relay: in a reactive service the inbound token lives in the Reactor context, not in a
    // thread-local, so it is read here, on the subscriber's context, before switching threads
    Mono<Optional<String>> relayed =
        auth.relay() && OutboundAuth.SECURITY
            ? ReactiveRelay.token().map(Optional::of).defaultIfEmpty(Optional.empty())
            : Mono.just(Optional.empty());
    return relayed.flatMap(
        token ->
            Mono.deferContextual(
                ctx -> {
                  RequestMetadata m = ctx.getOrDefault(RequestContext.KEY, null);
                  Mono<ClientRequest> prepared =
                      ReactiveStages.from(auth::headers)
                          .map(
                              credentials -> {
                                ClientRequest.Builder b = ClientRequest.from(request);
                                b.headers(
                                    h -> {
                                      for (Map.Entry<String, String> e :
                                          OutboundHeaders.of(m, settings, tenantHeader)
                                              .entrySet()) {
                                        if (!h.containsHeader(e.getKey())) {
                                          h.set(e.getKey(), e.getValue());
                                        }
                                      }
                                      credentials.forEach(
                                          (name, value) -> h.set(name, value.reveal()));
                                      token.ifPresent(
                                          t -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + t));
                                    });
                                return b.build();
                              });
                  return prepared.flatMap(req -> attempt(req, next, settings));
                }));
  }

  /** Spring Security access, isolated so the dependency stays optional. */
  static final class ReactiveRelay {
    private ReactiveRelay() {}

    /** Bearer token of the inbound request, from the reactive security context. */
    static Mono<String> token() {
      return ReactiveSecurityContextHolder.getContext()
          .mapNotNull(SecurityContext::getAuthentication)
          .filter(AbstractOAuth2TokenAuthenticationToken.class::isInstance)
          .map(a -> ((AbstractOAuth2TokenAuthenticationToken<?>) a).getToken().getTokenValue());
    }
  }

  private Mono<ClientResponse> attempt(
      ClientRequest req, ExchangeFunction next, ClientSettings settings) {
    String method = req.method().name();
    String key = req.headers().getFirst(StandardHeaders.IDEMPOTENCY_KEY);
    long first = System.nanoTime();
    int[] attempt = {0};
    return Mono.defer(
            () -> {
              attempt[0]++;
              resilience.acquire();
              long start = System.nanoTime();
              var released = new AtomicBoolean();
              return Mono.defer(() -> next.exchange(req))
                  .switchIfEmpty(
                      Mono.error(
                          new IllegalStateException("HTTP exchange completed without a response")))
                  .timeout(settings.timeouts().read())
                  .doOnCancel(
                      () -> {
                        if (released.compareAndSet(false, true))
                          resilience.release(start, new CancellationException("cancelled"), -1);
                      })
                  .doOnError(
                      e -> {
                        if (released.compareAndSet(false, true)) resilience.release(start, e, -1);
                        log(settings, method, req, -1, start, attempt[0]);
                      })
                  .flatMap(
                      res -> {
                        int status = res.statusCode().value();
                        if (released.compareAndSet(false, true))
                          resilience.release(start, null, status);
                        log(settings, method, req, status, start, attempt[0]);
                        if (status >= 400) {
                          return res.bodyToMono(String.class)
                              .defaultIfEmpty("")
                              .flatMap(
                                  body ->
                                      Mono.<ClientResponse>error(
                                          ExternalErrorTranslator.translate(
                                              settings.name(),
                                              status,
                                              res.headers()
                                                  .contentType()
                                                  .map(Object::toString)
                                                  .orElse(null),
                                              body)));
                        }
                        return Mono.just(res);
                      });
            })
        .retryWhen(
            Retry.from(
                signals ->
                    signals.concatMap(
                        s -> {
                          boolean again =
                              resilience.retry(
                                  method, key, s.failure(), -1, (int) s.totalRetries() + 1, first);
                          return again
                              ? Mono.delay(resilience.delay((int) s.totalRetries() + 1))
                              : Mono.error(s.failure());
                        })))
        // like the servlet client: a transport failure becomes a dependency error, so the service
        // answers 504 (timeout) or 503 (refused or reset connection) instead of an unknown failure
        .onErrorMap(e -> !(e instanceof AxizException), e -> transport(settings, e));
  }

  static ExternalServiceException transport(ClientSettings settings, Throwable e) {
    return ExternalServiceException.from(
        new ExternalErrorDetail(settings.name(), -1, e.getClass().getSimpleName(), null, ""), e);
  }

  private static void log(
      ClientSettings s, String method, ClientRequest req, int status, long start, int attempt) {
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "client={} method={} path={} status={} durationMs={} attempt={}",
          s.name(),
          method,
          req.url().getRawPath(),
          status,
          (System.nanoTime() - start) / 1_000_000,
          attempt);
    }
  }
}
