package pe.axiz.spring.httpclient;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.ClientSettings;

/**
 * Per-client behavior of a {@code RestClient}: header propagation, outbound authentication,
 * bulkhead and circuit breaker, retries only for idempotent calls within the total timeout, and one
 * log line per attempt (path only, never the query string). Must be the last interceptor.
 */
public class AxizRestClientInterceptor implements ClientHttpRequestInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.http.client");

  private final ClientResilience resilience;
  private final OutboundAuth auth;
  private final @Nullable String tenantHeader;

  public AxizRestClientInterceptor(
      ClientResilience resilience, OutboundAuth auth, @Nullable String tenantHeader) {
    this.resilience = Objects.requireNonNull(resilience);
    this.auth = Objects.requireNonNull(auth);
    this.tenantHeader = tenantHeader;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    ClientSettings settings = resilience.settings();
    HttpHeaders headers = request.getHeaders();
    for (Map.Entry<String, String> h :
        OutboundHeaders.of(RequestContext.get(), settings, tenantHeader).entrySet()) {
      if (!headers.containsHeader(h.getKey())) {
        headers.set(h.getKey(), h.getValue());
      }
    }
    auth.apply(headers::set);
    String method = request.getMethod().name();
    String idempotencyKey = headers.getFirst(StandardHeaders.IDEMPOTENCY_KEY);
    long first = System.nanoTime();
    for (int attempt = 1; ; attempt++) {
      resilience.acquire();
      long start = System.nanoTime();
      ClientHttpResponse response;
      try {
        response = execution.execute(request, body);
      } catch (IOException e) {
        resilience.release(start, e, -1);
        log(settings, method, request, -1, start, attempt);
        if (!resilience.retry(method, idempotencyKey, e, -1, attempt, first)) {
          throw transport(settings, e);
        }
        pause(resilience, attempt);
        continue;
      }
      int status = response.getStatusCode().value();
      resilience.release(start, null, status);
      log(settings, method, request, status, start, attempt);
      if (ClientResilience.retryableStatus(status)
          && resilience.retry(method, idempotencyKey, null, status, attempt, first)) {
        response.close();
        pause(resilience, attempt);
        continue;
      }
      return response;
    }
  }

  private static IOException transport(ClientSettings settings, IOException e) {
    ExternalServiceException ex =
        ExternalServiceException.from(
            new ExternalErrorDetail(settings.name(), -1, e.getClass().getSimpleName(), null, ""),
            e);
    return new IOException(ex.getMessage(), ex);
  }

  private static void pause(ClientResilience r, int attempt) throws InterruptedIOException {
    try {
      Thread.sleep(r.delay(attempt));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while waiting to retry");
    }
  }

  private static void log(
      ClientSettings s, String method, HttpRequest request, int status, long start, int attempt) {
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "client={} method={} path={} status={} durationMs={} attempt={}",
          s.name(),
          method,
          request.getURI().getRawPath(),
          status,
          (System.nanoTime() - start) / 1_000_000,
          attempt);
    }
  }
}
