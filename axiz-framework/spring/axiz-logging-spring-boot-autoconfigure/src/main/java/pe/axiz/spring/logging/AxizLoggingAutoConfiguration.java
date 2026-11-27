package pe.axiz.spring.logging;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import pe.axiz.core.api.HttpPayloadPolicy;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.logging.HttpLogFormatter;
import pe.axiz.core.logging.LogOperation;
import pe.axiz.core.logging.OperationLogger;
import pe.axiz.core.sensitive.ObjectMasker;

/**
 * {@link LogOperation} mediante un advisor de infraestructura (compatible con AOT y nativo, sin
 * AspectJ) y el log de acceso HTTP para aplicaciones servlet y reactivas.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.sensitive.AxizSensitiveAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.logging.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizLoggingProperties.class)
public class AxizLoggingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  static OperationLogger axizOperationLogger(ObjectProvider<ObjectMasker> masker) {
    return new OperationLogger(masker.getIfAvailable(ObjectMasker::defaults));
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor axizLogOperationAdvisor(
      ObjectProvider<OperationLogger> logger, ObjectProvider<FailureKind> failures) {
    ComposablePointcut pointcut =
        new ComposablePointcut(new AnnotationMatchingPointcut(LogOperation.class, true))
            .union(new AnnotationMatchingPointcut(null, LogOperation.class, true));
    LazyInterceptor interceptor =
        new LazyInterceptor(
            () ->
                new LogOperationInterceptor(
                    logger.getIfAvailable(() -> new OperationLogger(ObjectMasker.defaults())),
                    failures.getIfAvailable(FailureKind::axiz)));
    DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
    advisor.setOrder(0);
    return advisor;
  }

  @Bean
  @ConditionalOnMissingBean
  HttpLogFormatter axizHttpLogFormatter(
      AxizLoggingProperties properties, ObjectProvider<ObjectMasker> masker) {
    return new HttpLogFormatter(
        properties.http().headers(),
        properties.http().maxBodyLength(),
        masker.getIfAvailable(ObjectMasker::defaults));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  @ConditionalOnBooleanProperty(name = "axiz.logging.http.enabled", matchIfMissing = true)
  static class ServletConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HttpLoggingFilter axizHttpLoggingFilter(
        HttpLogFormatter formatter,
        AxizLoggingProperties properties,
        ObjectProvider<HttpPayloadPolicy> policies,
        Environment environment) {
      if (properties.http().bodies()) {
        // el enmascaramiento por nombre de clave y por patrón de número de tarjeta es una red de
        // seguridad, no una garantía: los cuerpos de forma libre pueden contener datos de
        // autenticación que nunca deben escribirse (PCI DSS 3.3.1)
        Environments.requireSingleProcess(
            environment.getProperty("axiz.config.environment"),
            "axiz.logging.http.bodies=true",
            "@LogOperation with masked arguments or audit events",
            "request and response bodies may carry cardholder or authentication data");
      }
      return new HttpLoggingFilter(formatter, properties.http(), policies.orderedStream().toList());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  @ConditionalOnBooleanProperty(name = "axiz.logging.http.enabled", matchIfMissing = true)
  static class ReactiveConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HttpLoggingWebFilter axizHttpLoggingWebFilter(
        HttpLogFormatter formatter, AxizLoggingProperties properties) {
      return new HttpLoggingWebFilter(formatter, properties.http());
    }
  }
}
