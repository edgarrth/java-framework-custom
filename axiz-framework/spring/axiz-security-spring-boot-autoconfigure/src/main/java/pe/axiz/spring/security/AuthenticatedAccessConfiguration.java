package pe.axiz.spring.security;

import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthenticatedReactiveAuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeReactiveMethodInterceptor;
import pe.axiz.core.security.AuthenticatedAccess;

/** Prior authentication enforcement; deliberately separate from expression-based coverage. */
@Configuration(proxyBeanMethods = false)
class AuthenticatedAccessConfiguration {
  private static Pointcut pointcut() {
    return new ComposablePointcut(
            AnnotationMatchingPointcut.forMethodAnnotation(AuthenticatedAccess.class))
        .union(new AnnotationMatchingPointcut(AuthenticatedAccess.class, true));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "jakarta.servlet.Servlet")
  static class Servlet {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static AuthorizationManagerBeforeMethodInterceptor axizAuthenticatedAccessInterceptor() {
      var interceptor =
          new AuthorizationManagerBeforeMethodInterceptor(
              pointcut(), AuthenticatedAuthorizationManager.authenticated());
      interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() - 1);
      return interceptor;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  static class Reactive {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static AuthorizationManagerBeforeReactiveMethodInterceptor
        axizAuthenticatedAccessInterceptor() {
      var interceptor =
          new AuthorizationManagerBeforeReactiveMethodInterceptor(
              pointcut(), AuthenticatedReactiveAuthorizationManager.authenticated());
      interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() - 1);
      return interceptor;
    }
  }
}
