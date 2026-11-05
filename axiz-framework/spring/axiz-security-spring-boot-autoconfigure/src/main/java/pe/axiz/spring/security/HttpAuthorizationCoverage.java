package pe.axiz.spring.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeReactiveMethodInterceptor;
import org.springframework.web.method.HandlerMethod;
import pe.axiz.core.security.PublicEndpoint;

/** Coverage inspects each mapped bean's own proxy, after startup has completed. */
final class HttpAuthorizationCoverage {
  record Endpoint(HandlerMethod handler, Set<String> paths, Set<String> methods) {}

  private HttpAuthorizationCoverage() {}

  static void verify(
      ApplicationContext context,
      AxizSecurityProperties properties,
      Collection<Endpoint> endpoints,
      boolean reactive,
      boolean effectiveFrameworkChain,
      String actuatorBase) {
    var missing = new ArrayList<String>();
    var publicPaths = new ArrayList<>(properties.anonymousPaths(actuatorBase));
    publicPaths.add(actuatorBase + "/health");
    publicPaths.add(actuatorBase + "/health/**");
    for (Endpoint endpoint : endpoints) {
      HandlerMethod handler = endpoint.handler().createWithResolvedBean();
      Object bean = handler.getBean();
      Class<?> target = AopProxyUtils.ultimateTargetClass(bean);
      if (infrastructure(target)) continue;
      boolean publicEndpoint =
          AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PublicEndpoint.class)
              || AnnotatedElementUtils.hasAnnotation(target, PublicEndpoint.class);
      boolean methodGuard = priorAdvisor(bean, handler, reactive, context);
      Set<String> methods =
          endpoint.methods().isEmpty()
              ? Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")
              : endpoint.methods();
      if (methods.contains("GET") && !methods.contains("HEAD")) {
        var implicit = new HashSet<>(methods);
        implicit.add("HEAD");
        methods = Set.copyOf(implicit);
      }
      for (String path : endpoint.paths()) {
        boolean permitted =
            effectiveFrameworkChain
                && publicPaths.stream().anyMatch(pattern -> covers(pattern, path));
        if (publicEndpoint) {
          if (!permitted) missing.add(path + " (PublicEndpoint has no effective permitAll rule)");
          continue;
        }
        if (methodGuard) continue;
        for (String method : methods) {
          boolean http =
              effectiveFrameworkChain
                  && !permitted
                  && properties.authorization().rules().stream()
                      .anyMatch(
                          rule ->
                              (rule.methods().isEmpty() || rule.methods().contains(method))
                                  && rule.paths().stream()
                                      .anyMatch(pattern -> covers(pattern, path)));
          if (!http)
            missing.add(
                method
                    + " "
                    + path
                    + " -> "
                    + target.getName()
                    + "#"
                    + handler.getMethod().getName());
        }
      }
    }
    if (missing.isEmpty()) return;
    String message =
        "HTTP endpoints without effective prior authorization: " + String.join(", ", missing);
    if (properties.authorization().unprotected().equals("fail"))
      throw new IllegalStateException(message);
    LoggerFactory.getLogger(HttpAuthorizationCoverage.class)
        .warn("{} (development warn mode)", message);
  }

  static boolean priorAdvisor(Object bean, HandlerMethod handler, boolean reactive) {
    return priorAdvisor(bean, handler, reactive, null);
  }

  private static boolean priorAdvisor(
      Object bean, HandlerMethod handler, boolean reactive, @Nullable ApplicationContext context) {
    if (!(bean instanceof Advised advised)) return false;
    Class<?> target = AopProxyUtils.ultimateTargetClass(bean);
    var method = AopUtils.getMostSpecificMethod(handler.getMethod(), target);
    for (var advisor : advised.getAdvisors()) {
      if (!(advisor instanceof PointcutAdvisor pointcut)) continue;
      Object advice = advisor.getAdvice();
      boolean before =
          reactive
              ? advisor instanceof AuthorizationManagerBeforeReactiveMethodInterceptor
                  || advice instanceof AuthorizationManagerBeforeReactiveMethodInterceptor
              : advisor instanceof AuthorizationManagerBeforeMethodInterceptor
                  || advice instanceof AuthorizationManagerBeforeMethodInterceptor;
      before = before || deferredBefore(advice, reactive, context);
      if (before
          && !pointcut.getPointcut().getMethodMatcher().isRuntime()
          && pointcut.getPointcut().getClassFilter().matches(target)
          && pointcut.getPointcut().getMethodMatcher().matches(method, target)) return true;
    }
    return false;
  }

  /**
   * Spring 7 wraps native interceptors lazily: match the exact advice instance, without reflection.
   */
  private static boolean deferredBefore(
      Object advice, boolean reactive, @Nullable ApplicationContext context) {
    if (context == null
        || !advice
            .getClass()
            .getName()
            .equals(
                "org.springframework.security.config.annotation.method.configuration.DeferringMethodInterceptor"))
      return false;
    List<String> names =
        reactive
            ? List.of("preAuthorizeAuthorizationMethodInterceptor")
            : List.of(
                "preAuthorizeAuthorizationMethodInterceptor",
                "securedAuthorizationMethodInterceptor",
                "jsr250AuthorizationMethodInterceptor");
    return names.stream()
        .anyMatch(name -> context.containsBean(name) && context.getBean(name) == advice);
  }

  /** Conservative inclusion proof: equal patterns or a literal parent with a recursive wildcard. */
  static boolean covers(String rule, String mapping) {
    if (rule.equals(mapping)) return true;
    if (rule.equals("/**")) return true;
    if (!rule.endsWith("/**")) return false;
    String prefix = rule.substring(0, rule.length() - 3);
    if (prefix.contains("*") || prefix.contains("{") || prefix.contains("?")) return false;
    return mapping.equals(prefix) || mapping.startsWith(prefix + "/");
  }

  private static boolean infrastructure(Class<?> target) {
    return target.getName().startsWith("org.springframework.boot.")
        || target.getName().startsWith("org.springdoc.")
        || target.getName().equals("pe.axiz.spring.errors.ProblemErrorController");
  }
}
