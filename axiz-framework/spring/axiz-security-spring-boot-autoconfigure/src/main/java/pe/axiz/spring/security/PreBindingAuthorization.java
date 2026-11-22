package pe.axiz.spring.security;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.web.method.HandlerMethod;

/**
 * Decides which handler methods are authorized before their arguments are read.
 *
 * <p>Spring resolves and validates {@code @RequestBody} and other arguments before the
 * {@code @PreAuthorize} interceptor of the controller runs: a caller without the required scope
 * would receive a 400 listing the fields of the contract instead of a 403. A {@code @PreAuthorize}
 * expression that does not read method arguments ({@code #name}, {@code #p0}) is evaluated against
 * the handler before binding, so an unauthorized caller learns nothing about the endpoint. The
 * method interceptor still evaluates it afterwards; expressions that read arguments keep that
 * single, later evaluation.
 */
final class PreBindingAuthorization {

  private final Map<Method, Boolean> eligible = new ConcurrentHashMap<>();

  /** Whether the handler's effective {@code @PreAuthorize} can be decided without arguments. */
  boolean appliesTo(HandlerMethod handler) {
    return eligible.computeIfAbsent(handler.getMethod(), method -> decidable(handler));
  }

  /** An invocation of the handler with no arguments bound, for argument-free expressions. */
  static MethodInvocation invocation(HandlerMethod handler) {
    Method method = handler.getMethod();
    return new SimpleMethodInvocation(
        handler.getBean(), method, new Object[method.getParameterCount()]);
  }

  private static boolean decidable(HandlerMethod handler) {
    Method method = handler.getMethod();
    PreAuthorize own = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
    PreAuthorize effective =
        own != null
            ? own
            : AnnotatedElementUtils.findMergedAnnotation(
                AopUtils.getTargetClass(handler.getBean()), PreAuthorize.class);
    if (effective == null) return false;
    String expression = effective.value();
    return !expression.contains("#")
        && !expression.contains("returnObject")
        && !expression.contains("filterObject");
  }
}
