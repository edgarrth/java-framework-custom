package pe.axiz.spring.idempotency;

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
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.PayloadProtector;
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
public final class IdempotencyMethodInterceptor implements MethodInterceptor {

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
  private final Supplier<PayloadProtector> protection;
  private final Supplier<AsyncPayloadProtector> asyncProtection;
  private final Supplier<AsyncIdempotencyService> asyncService;

  public IdempotencyMethodInterceptor(Supplier<IdempotencyService> service) {
    this(
        service,
        () -> {
          throw new IllegalStateException("Reactive idempotency requires AsyncIdempotencyService");
        });
  }

  public IdempotencyMethodInterceptor(
      Supplier<IdempotencyService> service, Supplier<AsyncIdempotencyService> asyncService) {
    this(service, asyncService, () -> PayloadProtector.NONE, () -> AsyncPayloadProtector.NONE);
  }

  public IdempotencyMethodInterceptor(
      Supplier<IdempotencyService> service,
      Supplier<AsyncIdempotencyService> asyncService,
      Supplier<PayloadProtector> protection,
      Supplier<AsyncPayloadProtector> asyncProtection) {
    this.protection = Objects.requireNonNull(protection);
    this.asyncProtection = Objects.requireNonNull(asyncProtection);
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
      return ReactiveIdempotency.apply(invocation, rule, operation, asyncService, asyncProtection);
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
    String scope = IdempotencySupport.scope(operation, RequestContext.get());
    var binding = IdempotencyPayloads.Binding.of(operation, scope, key);
    IdempotencyService.Decision decision = service.get().begin(scope, key, fp);
    if (decision instanceof IdempotencyService.Replay replay) {
      throw new IdempotentReplay(
          IdempotencyPayloads.reveal(replay.response(), binding, protection.get()));
    }
    request.setAttribute(IdempotencyPayloads.BINDING_ATTRIBUTE, binding);
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
