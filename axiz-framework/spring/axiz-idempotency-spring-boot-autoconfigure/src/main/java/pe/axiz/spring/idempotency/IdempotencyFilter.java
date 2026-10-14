package pe.axiz.spring.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.StoredResponse;

/**
 * Buffers the body of unsafe requests carrying {@code Idempotency-Key} (up to {@code maxBodySize})
 * so it can be fingerprinted (method, path, query string as sent and body), and stores the response
 * once the handler finished. The decision itself is taken by {@link IdempotencyMethodInterceptor}
 * after method authorization.
 *
 * <p>The response streams to the client as usual; only a copy of up to {@code maxResponseSize}
 * bytes is kept for storage. Only a handler that returned normally with a status below 500 is
 * stored. An exception that escapes the chain, or a server error, releases the key so the client
 * can retry. Asynchronous handlers are stored when their last dispatch finishes.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);

  private final IdempotencyService service;
  private final int maxBodySize;
  private final int maxResponseSize;

  public IdempotencyFilter(IdempotencyService service, int maxBodySize, int maxResponseSize) {
    this.service = Objects.requireNonNull(service);
    this.maxBodySize = maxBodySize;
    this.maxResponseSize = maxResponseSize;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !IdempotencySupport.unsafe(request.getMethod())
        || request.getHeader(StandardHeaders.IDEMPOTENCY_KEY) == null;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isAsyncDispatch(request)) {
      CapturingResponse captured = WebUtils.getNativeResponse(response, CapturingResponse.class);
      run(
          request,
          captured != null ? captured : new CapturingResponse(response, maxResponseSize),
          chain);
      return;
    }
    byte[] body;
    try (InputStream in = request.getInputStream()) {
      body = in.readNBytes(maxBodySize + 1);
    }
    if (body.length > maxBodySize) {
      response.sendError(413);
      return;
    }
    CachedBodyRequest cached = new CachedBodyRequest(request, body);
    cached.setAttribute(
        IdempotencySupport.FINGERPRINT_ATTRIBUTE,
        IdempotencyService.fingerprint(
            request.getMethod(), request.getRequestURI(), request.getQueryString(), body));
    run(cached, new CapturingResponse(response, maxResponseSize), chain);
  }

  private void run(HttpServletRequest request, CapturingResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean completedNormally = false;
    try {
      chain.doFilter(request, response);
      completedNormally = true;
    } finally {
      if (!isAsyncStarted(request)) {
        response.flushWriter();
        finish(request, response, completedNormally);
      }
    }
  }

  private void finish(HttpServletRequest request, CapturingResponse response, boolean normally) {
    if (!(request.getAttribute(IdempotencySupport.KEY_ATTRIBUTE)
        instanceof IdempotencyService.Proceed execution)) {
      return; // not idempotent, rejected before deciding, or a replay
    }
    request.removeAttribute(IdempotencySupport.KEY_ATTRIBUTE);
    try {
      int status = response.getStatus();
      if (!normally || status >= 500) {
        service.fail(execution);
        return;
      }
      BoundedCapture capture = response.capture();
      if (!service.complete(
          execution,
          capture.overflowed()
              ? StoredResponse.withoutBody(
                  status, IdempotencySupport.replayedHeaders(response::getHeader))
              : new StoredResponse(
                  status,
                  IdempotencySupport.replayedHeaders(response::getHeader),
                  capture.bytes()))) {
        LOG.warn(
            "Idempotency key lost before completion (lock expired); response not stored: {}",
            execution.key());
      }
    } catch (RuntimeException e) {
      // the original outcome must reach the client; the lock expires by itself
      LOG.error("Idempotency store unavailable while finishing {}", execution.key(), e);
    }
  }
}
