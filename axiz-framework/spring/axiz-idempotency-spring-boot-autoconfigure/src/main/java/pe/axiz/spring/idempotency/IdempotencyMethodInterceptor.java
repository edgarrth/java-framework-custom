package pe.axiz.spring.idempotency;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NoEffect;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyErrors;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.Idempotent;

/**
 * Applies {@code @Idempotent} around the handler method, <b>inside</b> method security: it is
 * ordered after Spring Security's {@code @PreAuthorize}, {@code @Secured} and JSR-250 interceptors,
 * so a replay is only returned when the caller is still authorized for this request, with the
 * resolved arguments (path variables, body) the policies need. Then it rejects missing keys when
 * required, replays completed responses (through {@link IdempotentReplay}), answers 409 while the
 * first request runs and 422 when a key is reused with a different request.
 *
 * <p>Servlet requests take the fingerprint computed by {@link IdempotencyFilter}; reactive handlers
 * (returning {@code Mono} or {@code Flux}) take it from {@link IdempotencyWebFilter} through the
 * Reactor context.
 */
public class IdempotencyMethodInterceptor implements MethodInterceptor {

  /** After {@code AuthorizationInterceptorsOrder.JSR250} (400), before result authorization. */
  public static final int ORDER = 440;

  private static final boolean REACTOR =
      ClassUtils.isPresent(
          "reactor.core.publisher.Mono", IdempotencyMethodInterceptor.class.getClassLoader());

  // servlet types are only touched when the servlet API is present (reactive services lack it)
  private static final boolean SERVLET =
      ClassUtils.isPresent(
          "jakarta.servlet.http.HttpServletRequest",
          IdempotencyMethodInterceptor.class.getClassLoader());

  private final Supplier<IdempotencyService> service;
  private final Supplier<AsyncIdempotencyService> asyncService;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public IdempotencyMethodInterceptor(Supplier<IdempotencyService> service) {
    this(
        service,
        () -> {
          throw new IllegalStateException("Reactive idempotency requires AsyncIdempotencyService");
        });
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Spring advice extension validates service suppliers; no external resource is "
              + "acquired or this reference published.")
  public IdempotencyMethodInterceptor(
      Supplier<IdempotencyService> service, Supplier<AsyncIdempotencyService> asyncService) {
    this.service = Objects.requireNonNull(service);
    this.asyncService = Objects.requireNonNull(asyncService);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Method method = invocation.getMethod();
    Object target = invocation.getThis();
    Class<?> targetClass =
        target != null ? AopUtils.getTargetClass(target) : method.getDeclaringClass();
    Idempotent rule = IdempotencySupport.annotation(method, targetClass);
    if (rule == null) {
      return invocation.proceed();
    }
    String operation = IdempotencySupport.operation(method, targetClass);
    if (SERVLET) {
      HttpServletRequest request = ServletRequests.current();
      if (request != null) {
        return servlet(invocation, request, rule, operation);
      }
    }
    if (REACTOR && ReactiveIdempotency.handles(method.getReturnType())) {
      return ReactiveIdempotency.apply(invocation, rule, operation, asyncService);
    }
    return invocation.proceed(); // not called from a web request
  }

  private @Nullable Object servlet(
      MethodInvocation invocation, HttpServletRequest request, Idempotent rule, String operation)
      throws Throwable {
    if (!IdempotencySupport.unsafe(request.getMethod())
        || request.getAttribute(IdempotencySupport.KEY_ATTRIBUTE) != null) {
      return invocation.proceed();
    }
    String key = request.getHeader(StandardHeaders.IDEMPOTENCY_KEY);
    Object fingerprint = request.getAttribute(IdempotencySupport.FINGERPRINT_ATTRIBUTE);
    if (key == null || !(fingerprint instanceof String fp)) {
      if (rule.required()) {
        throw new BusinessException(IdempotencyErrors.KEY_REQUIRED, "Idempotency-Key missing");
      }
      return invocation.proceed();
    }
    IdempotencyService.Decision decision =
        service.get().begin(IdempotencySupport.scope(operation, RequestContext.get()), key, fp);
    if (decision instanceof IdempotencyService.Replay replay) {
      throw new IdempotentReplay(replay.response());
    }
    request.setAttribute(IdempotencySupport.KEY_ATTRIBUTE, decision);
    request.setAttribute(
        IdempotencyFilter.LEASE_ATTRIBUTE,
        service.get().startLease((IdempotencyService.Proceed) decision));
    try {
      return invocation.proceed();
    } catch (Throwable failure) {
      if (failure instanceof NoEffect)
        request.setAttribute(IdempotencyFilter.NO_EFFECT_ATTRIBUTE, true);
      throw failure;
    }
  }

  /** Current servlet request; only loaded when the servlet API is present. */
  private static final class ServletRequests {
    private ServletRequests() {}

    static @Nullable HttpServletRequest current() {
      RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
      return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
  }
}
