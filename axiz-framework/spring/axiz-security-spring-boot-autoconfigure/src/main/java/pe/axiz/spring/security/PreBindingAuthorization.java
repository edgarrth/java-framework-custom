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
 * Decide qué métodos handler están autorizados antes de leer sus argumentos.
 *
 * <p>Spring resuelve y valida {@code @RequestBody} y los demás argumentos antes de que se ejecute
 * el interceptor {@code @PreAuthorize} del controlador: un invocante sin el scope requerido
 * recibiría un 400 que lista los campos del contrato en lugar de un 403. Una expresión
 * {@code @PreAuthorize} que no lee argumentos del método ({@code #name}, {@code #p0}) se evalúa
 * contra el handler antes del binding, de modo que un invocante no autorizado no averigua nada
 * sobre el endpoint. El interceptor de métodos igualmente la evalúa después; las expresiones que
 * leen argumentos conservan esa única evaluación posterior.
 */
final class PreBindingAuthorization {

  private final Map<Method, Boolean> eligible = new ConcurrentHashMap<>();

  /** Indica si el {@code @PreAuthorize} efectivo del handler puede decidirse sin argumentos. */
  boolean appliesTo(HandlerMethod handler) {
    return eligible.computeIfAbsent(handler.getMethod(), method -> decidable(handler));
  }

  /** Invocación del handler sin argumentos enlazados, para expresiones sin argumentos. */
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
