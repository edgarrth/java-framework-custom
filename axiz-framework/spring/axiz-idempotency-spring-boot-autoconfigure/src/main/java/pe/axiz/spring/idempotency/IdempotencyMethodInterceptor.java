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
 * Aplica {@code @Idempotent} alrededor del método handler, <b>dentro</b> de la seguridad de
 * métodos: se ordena después de los interceptores {@code @PreAuthorize}, {@code @Secured} y JSR-250
 * de Spring Security, de modo que una reproducción solo se devuelve cuando el invocante sigue
 * autorizado para esta solicitud, con los argumentos resueltos (variables de ruta, cuerpo) que
 * necesitan las políticas. Luego rechaza las claves ausentes cuando son obligatorias, reproduce las
 * respuestas completadas (mediante {@link IdempotentReplay}), responde 409 mientras se ejecuta la
 * primera solicitud y 422 cuando se reutiliza una clave con una solicitud distinta.
 *
 * <p>Las solicitudes servlet toman la huella calculada por {@link IdempotencyFilter}; los handlers
 * reactivos (que devuelven {@code Mono} o {@code Flux}) la toman de {@link IdempotencyWebFilter}
 * mediante el contexto de Reactor.
 */
public final class IdempotencyMethodInterceptor implements MethodInterceptor {

  /**
   * Después de {@code AuthorizationInterceptorsOrder.JSR250} (400), antes de la autorización del
   * resultado.
   */
  public static final int ORDER = 440;

  private static final boolean REACTOR =
      ClassUtils.isPresent(
          "reactor.core.publisher.Mono", IdempotencyMethodInterceptor.class.getClassLoader());

  // los tipos de servlet solo se usan cuando la API de servlet está presente (los servicios
  // reactivos no la tienen)
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
    return invocation.proceed(); // no se invoca desde una solicitud web
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

  /** Solicitud servlet actual; solo se carga cuando la API de servlet está presente. */
  private static final class ServletRequests {
    private ServletRequests() {}

    static @Nullable HttpServletRequest current() {
      RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
      return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
  }
}
