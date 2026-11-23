package pe.axiz.spring.idempotency;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;
import pe.axiz.core.api.HttpPayloadPolicy;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.idempotency.IdempotencyLease;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.StoredResponse;

/**
 * Almacena en búfer el cuerpo de las solicitudes no seguras que llevan {@code Idempotency-Key}
 * (hasta {@code maxBodySize}) para poder calcular su huella (método, ruta, query string tal como se
 * envió y cuerpo), y almacena la respuesta cuando el handler termina. La decisión en sí la toma
 * {@link IdempotencyMethodInterceptor} después de la autorización de métodos.
 *
 * <p>La respuesta se transmite al cliente como de costumbre; solo se conserva para almacenamiento
 * una copia de hasta {@code maxResponseSize} bytes. Solo se almacena un handler que retornó
 * normalmente con un estado inferior a 500. Una excepción que escapa de la cadena, o un error del
 * servidor, conserva la clave como UNKNOWN hasta que el servicio concilia la operación. Los
 * handlers asíncronos se almacenan cuando termina su último dispatch.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

  static final String LEASE_ATTRIBUTE = IdempotencyFilter.class.getName() + ".lease";
  static final String NO_EFFECT_ATTRIBUTE = IdempotencyFilter.class.getName() + ".noEffect";

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);

  private final IdempotencyService service;
  private final int maxBodySize;
  private final int maxResponseSize;
  private final List<HttpPayloadPolicy> payloadPolicies;
  private final PayloadProtector protection;

  public IdempotencyFilter(
      IdempotencyService service,
      int maxBodySize,
      int maxResponseSize,
      List<HttpPayloadPolicy> payloadPolicies) {
    this(service, maxBodySize, maxResponseSize, payloadPolicies, PayloadProtector.NONE);
  }

  public IdempotencyFilter(
      IdempotencyService service,
      int maxBodySize,
      int maxResponseSize,
      List<HttpPayloadPolicy> payloadPolicies,
      PayloadProtector protection) {
    this.protection = Objects.requireNonNull(protection);
    this.service = Objects.requireNonNull(service);
    this.maxBodySize = maxBodySize;
    this.maxResponseSize = maxResponseSize;
    this.payloadPolicies = List.copyOf(payloadPolicies);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !IdempotencySupport.unsafe(request.getMethod())
        || payloadPolicies.stream()
            .anyMatch(
                p ->
                    !p.permitsCapture(
                        request.getRequestURI().substring(request.getContextPath().length())))
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
      Arrays.fill(body, (byte) 0);
      response.sendError(413);
      return;
    }
    CachedBodyRequest cached = new CachedBodyRequest(request, body);
    cached.setAttribute(
        IdempotencySupport.FINGERPRINT_ATTRIBUTE,
        IdempotencyService.fingerprint(
            request.getMethod(), request.getRequestURI(), request.getQueryString(), body));
    Arrays.fill(body, (byte) 0);
    run(cached, new CapturingResponse(response, maxResponseSize), chain);
  }

  private void run(HttpServletRequest request, CapturingResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean completedNormally = false;
    try {
      chain.doFilter(request, response);
      completedNormally = true;
    } finally {
      if (isAsyncStarted(request)) {
        registerAsyncCleanup(request, response);
      } else {
        boolean flushed = false;
        try {
          response.flushWriter();
          flushed = true;
        } finally {
          finish(request, response, completedNormally && flushed);
        }
      }
    }
  }

  private void registerAsyncCleanup(HttpServletRequest request, CapturingResponse response) {
    String listenerKey = IdempotencyFilter.class.getName() + ".asyncListener";
    if (request.getAttribute(listenerKey) != null) return;
    AsyncListener listener =
        new AsyncListener() {
          @Override
          public void onComplete(AsyncEvent event) {
            finish(request, response, true);
          }

          @Override
          public void onTimeout(AsyncEvent event) {
            finish(request, response, false);
          }

          @Override
          public void onError(AsyncEvent event) {
            finish(request, response, false);
          }

          @Override
          public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
          }
        };
    request.setAttribute(listenerKey, listener);
    try {
      request.getAsyncContext().addListener(listener);
    } catch (IllegalStateException alreadyCompleted) {
      finish(request, response, false);
    }
  }

  private void finish(HttpServletRequest request, CapturingResponse response, boolean normally) {
    IdempotencyService.Proceed execution;
    synchronized (request) {
      if (!(request.getAttribute(IdempotencySupport.KEY_ATTRIBUTE)
          instanceof IdempotencyService.Proceed current)) {
        clear(request, response);
        return;
      }
      execution = current;
      request.removeAttribute(IdempotencySupport.KEY_ATTRIBUTE);
    }
    try {
      int status = response.getStatus();
      if (Boolean.TRUE.equals(request.getAttribute(NO_EFFECT_ATTRIBUTE))) {
        service.releaseWithoutEffect(execution);
        return;
      }
      if (!normally || status >= 500) {
        service.fail(execution);
        return;
      }
      BoundedCapture capture = response.capture();
      if (!service.complete(
          execution,
          IdempotencyPayloads.store(
              status,
              capture.overflowed()
                  ? StoredResponse.withoutBody(
                          status, IdempotencySupport.replayedHeaders(response::getHeader))
                      .headers()
                  : IdempotencySupport.replayedHeaders(response::getHeader),
              capture.overflowed() ? new byte[0] : capture.bytes(),
              (IdempotencyPayloads.Binding)
                  Objects.requireNonNull(
                      request.getAttribute(IdempotencyPayloads.BINDING_ATTRIBUTE),
                      "Missing idempotency record binding"),
              protection))) {
        LOG.warn("Idempotency lease lost before completion; response not stored");
      }
    } catch (RuntimeException e) {
      // el resultado original debe llegar al cliente; la retención sigue impidiendo la
      // reutilización inmediata
      LOG.error("Idempotency completion failed; failureType={}", e.getClass().getSimpleName());
      try {
        service.fail(execution);
      } catch (RuntimeException unavailable) {
        LOG.error(
            "Idempotency uncertainty could not be recorded; failureType={}",
            unavailable.getClass().getSimpleName());
      }
    } finally {
      if (request.getAttribute(LEASE_ATTRIBUTE) instanceof IdempotencyLease lease) {
        lease.close();
        request.removeAttribute(LEASE_ATTRIBUTE);
      }
      clear(request, response);
    }
  }

  private static void clear(HttpServletRequest request, CapturingResponse response) {
    response.capture().close();
    var cached = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
    if (cached != null) cached.close();
  }
}
