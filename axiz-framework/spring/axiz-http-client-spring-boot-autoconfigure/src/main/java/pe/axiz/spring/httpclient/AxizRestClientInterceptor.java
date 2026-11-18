package pe.axiz.spring.httpclient;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;

/**
 * Per-client behavior of a {@code RestClient}: header propagation, outbound authentication,
 * bulkhead and circuit breaker, retries only for idempotent calls within the total timeout, and one
 * log line per attempt (path only, never the query string). Must be the last interceptor.
 */
public final class AxizRestClientInterceptor implements ClientHttpRequestInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.http.client");

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ClientResilience is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
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
    settings
        .transportSecurity()
        .validateTarget(
            settings.baseUrl(),
            request.getURI(),
            settings.auth().type() != AuthType.NONE,
            "HTTP client " + settings.name());
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
      ClientHttpResponse response = null;
      Throwable failure = null;
      boolean delivered = false;
      int status = -1;
      try {
        try {
          response = execution.execute(request, body);
          status = response.getStatusCode().value();
        } catch (IOException | RuntimeException | Error problem) {
          failure = problem;
        }
        // One notification per successful acquisition, even when status decoding fails.
        resilience.release(start, failure, status);
        log(settings, method, request, status, start, attempt);
        if (failure != null) {
          if (failure instanceof IOException io) {
            if (!resilience.retry(method, idempotencyKey, io, -1, attempt, first))
              throw transport(settings, io);
          } else if (failure instanceof RuntimeException runtime) throw runtime;
          else throw (Error) failure;
        } else if (!ClientResilience.retryableStatus(status)
            || !resilience.retry(method, idempotencyKey, null, status, attempt, first)) {
          delivered = true;
          return Objects.requireNonNull(response);
        }
        // Return the connection before backoff, not after waiting for the next attempt.
        var abandoned = response;
        response = null;
        close(abandoned, failure);
        pause(resilience, attempt);
      } catch (IOException | RuntimeException | Error problem) {
        failure = problem;
        throw problem;
      } finally {
        if (!delivered) close(response, failure);
      }
    }
  }

  private static void close(@Nullable ClientHttpResponse response, @Nullable Throwable failure) {
    if (response == null) return;
    try {
      response.close();
    } catch (RuntimeException | Error cleanup) {
      if (failure == null) throw cleanup;
      if (failure != cleanup) failure.addSuppressed(cleanup);
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
          ClientLogRoute.of(
              request.getAttributes().get("org.springframework.web.client.RestClient.uriTemplate"),
              s.baseUrl()),
          status,
          (System.nanoTime() - start) / 1_000_000,
          attempt);
    }
  }
}
