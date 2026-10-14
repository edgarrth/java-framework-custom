package pe.axiz.spring.httpclient;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.http.ExternalErrorTranslator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Reactive counterpart of {@link AxizRestClientInterceptor}, without blocking: credentials that
 * need I/O are obtained on a worker scheduler, errors are translated from the response body and
 * retries use Reactor's backoff for idempotent calls only.
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
    return Mono.deferContextual(
        ctx -> {
          RequestMetadata m = ctx.getOrDefault(RequestContext.KEY, null);
          Mono<ClientRequest> prepared =
              Mono.fromCallable(
                      () -> {
                        ClientRequest.Builder b = ClientRequest.from(request);
                        b.headers(
                            h -> {
                              for (Map.Entry<String, String> e :
                                  OutboundHeaders.of(m, settings, tenantHeader).entrySet()) {
                                if (!h.containsHeader(e.getKey())) {
                                  h.set(e.getKey(), e.getValue());
                                }
                              }
                              auth.apply(h::set);
                            });
                        return b.build();
                      })
                  .subscribeOn(Schedulers.boundedElastic());
          return prepared.flatMap(req -> attempt(req, next, settings));
        });
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
              return next.exchange(req)
                  .timeout(settings.timeouts().read())
                  .doOnError(e -> resilience.release(start, e, -1))
                  .flatMap(
                      res -> {
                        int status = res.statusCode().value();
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
                        })));
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
