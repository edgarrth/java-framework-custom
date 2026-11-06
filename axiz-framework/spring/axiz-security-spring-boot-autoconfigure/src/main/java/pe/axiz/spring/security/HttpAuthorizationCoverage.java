package pe.axiz.spring.security;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeReactiveMethodInterceptor;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.core.annotation.SecurityAnnotationScanners;
import org.springframework.web.method.HandlerMethod;
import pe.axiz.core.security.AuthenticatedAccess;
import pe.axiz.core.security.PublicEndpoint;
import pe.axiz.spring.security.AuthorizationExpressionClassifier.Access;

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
      Set<Family> families = priorAdvisors(bean, handler, reactive, context);
      boolean authenticated =
          AnnotatedElementUtils.hasAnnotation(handler.getMethod(), AuthenticatedAccess.class)
              || AnnotatedElementUtils.hasAnnotation(target, AuthenticatedAccess.class);
      Access access = effectiveAccess(handler, target, families, context, authenticated);
      boolean methodGuard =
          families.contains(Family.AUTHENTICATED_ACCESS) || access.protectedAccess();
      if (authenticated && publicEndpoint)
        throw new IllegalStateException(
            "AuthenticatedAccess conflicts with PublicEndpoint: " + handler);
      if (authenticated && !families.contains(Family.AUTHENTICATED_ACCESS))
        throw new IllegalStateException(
            "AuthenticatedAccess has no effective interceptor: " + handler);
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
        if (authenticated && publicPaths.stream().anyMatch(pattern -> covers(pattern, path)))
          throw new IllegalStateException(
              "AuthenticatedAccess conflicts with public-paths: " + path);
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
                    + handler.getMethod().getName()
                    + " (method policy: "
                    + access
                    + ")");
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

  enum Family {
    PRE_AUTHORIZE("preAuthorizeAuthorizationMethodInterceptor"),
    SECURED("securedAuthorizationMethodInterceptor"),
    JSR250("jsr250AuthorizationMethodInterceptor"),
    AUTHENTICATED_ACCESS("axizAuthenticatedAccessInterceptor");

    final String beanName;

    Family(String beanName) {
      this.beanName = beanName;
    }
  }

  static boolean priorAdvisor(Object bean, HandlerMethod handler, boolean reactive) {
    return !priorAdvisors(bean, handler, reactive, null).isEmpty();
  }

  private static Set<Family> priorAdvisors(
      Object bean, HandlerMethod handler, boolean reactive, @Nullable ApplicationContext context) {
    var found = EnumSet.noneOf(Family.class);
    if (!(bean instanceof Advised advised) || context == null) return found;
    Class<?> target = AopProxyUtils.ultimateTargetClass(bean);
    Method method = AopUtils.getMostSpecificMethod(handler.getMethod(), target);
    for (var advisor : advised.getAdvisors()) {
      if (!(advisor instanceof PointcutAdvisor pointcut)
          || pointcut.getPointcut().getMethodMatcher().isRuntime()
          || !pointcut.getPointcut().getClassFilter().matches(target)
          || !pointcut.getPointcut().getMethodMatcher().matches(method, target)) continue;
      Object advice = advisor.getAdvice();
      for (Family family : Family.values()) {
        if (reactive && (family == Family.SECURED || family == Family.JSR250)) continue;
        if (!context.containsBean(family.beanName)) continue;
        Object registered = context.getBean(family.beanName);
        if (registered != advisor && registered != advice) continue;
        boolean nativeInterceptor =
            reactive
                ? registered instanceof AuthorizationManagerBeforeReactiveMethodInterceptor
                : registered instanceof AuthorizationManagerBeforeMethodInterceptor;
        boolean deferred =
            registered
                .getClass()
                .getName()
                .equals(
                    "org.springframework.security.config.annotation.method.configuration.DeferringMethodInterceptor");
        if (!nativeInterceptor && !deferred) continue;
        if (family == Family.SECURED && !standardSecured(context, family.beanName))
          throw new IllegalStateException(
              "Unsupported custom @Secured interceptor: " + family.beanName);
        found.add(family);
      }
    }
    return found;
  }

  private static boolean standardSecured(ApplicationContext context, String name) {
    if (!(context instanceof ConfigurableApplicationContext configurable)) return false;
    var factory = configurable.getBeanFactory();
    if (!factory.containsBeanDefinition(name)) return false;
    var definition = factory.getBeanDefinition(name);
    String owner = definition.getFactoryBeanName();
    Class<?> ownerType = owner == null ? null : context.getType(owner);
    String declaringType = ownerType == null ? definition.getBeanClassName() : ownerType.getName();
    return declaringType != null
        && declaringType.equals(
            "org.springframework.security.config.annotation.method.configuration."
                + "SecuredMethodSecurityConfiguration");
  }

  private static Access effectiveAccess(
      HandlerMethod handler,
      Class<?> target,
      Set<Family> families,
      ApplicationContext context,
      boolean authenticated) {
    Method method = AopUtils.getMostSpecificMethod(handler.getMethod(), target);
    Access combined = Access.TRUE;
    for (Family family : families) {
      Access access =
          switch (family) {
            case AUTHENTICATED_ACCESS -> Access.OPEN;
            case SECURED -> Access.RESTRICTED;
            case JSR250 -> {
              var denied =
                  SecurityAnnotationScanners.requireUnique(
                          jakarta.annotation.security.DenyAll.class)
                      .scan(method, target);
              var roles =
                  SecurityAnnotationScanners.requireUnique(
                          jakarta.annotation.security.RolesAllowed.class)
                      .scan(method, target);
              yield denied != null ? Access.FALSE : roles != null ? Access.RESTRICTED : Access.TRUE;
            }
            case PRE_AUTHORIZE -> {
              var defaults =
                  context
                      .getBeanProvider(AnnotationTemplateExpressionDefaults.class)
                      .getIfAvailable();
              var scanner =
                  defaults == null
                      ? SecurityAnnotationScanners.requireUnique(PreAuthorize.class)
                      : SecurityAnnotationScanners.requireUnique(PreAuthorize.class, defaults);
              PreAuthorize annotation = scanner.scan(method, target);
              if (annotation == null)
                throw new IllegalStateException(
                    "PreAuthorize advisor has no declaration: " + handler);
              Access classified =
                  new AuthorizationExpressionClassifier().classify(annotation.value());
              if (authenticated && classified == Access.TRUE)
                throw new IllegalStateException(
                    "AuthenticatedAccess conflicts with TRUE expression: " + handler);
              yield classified;
            }
          };
      combined = combined.and(access);
    }
    return combined;
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
