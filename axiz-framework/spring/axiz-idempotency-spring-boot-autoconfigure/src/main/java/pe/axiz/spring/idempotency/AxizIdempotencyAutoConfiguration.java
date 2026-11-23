package pe.axiz.spring.idempotency;

import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import pe.axiz.core.api.HttpPayloadPolicy;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.idempotency.AsyncIdempotencyResolver;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyResolver;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Operaciones {@code @Idempotent} con la cabecera {@code Idempotency-Key}: respuestas almacenadas
 * en ElastiCache (Redis) o DynamoDB y reproducidas; 400, 409 y 422 ante un uso indebido.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnBooleanProperty(name = "axiz.idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizIdempotencyProperties.class,
  AxizIdempotencyProtectionProperties.class
})
public class AxizIdempotencyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  IdempotencyStore axizInMemoryIdempotencyStore(
      AxizIdempotencyProperties p, ObjectProvider<Clock> clock, Environment env) {
    if (!"memory".equals(p.store())) {
      throw new IllegalStateException(
          "axiz.idempotency.store="
              + p.store()
              + " is not available: add its client and connection settings");
    }
    Environments.requireSingleProcess(
        env.getProperty("axiz.config.environment"),
        "axiz.idempotency.store=memory",
        "redis or dynamodb",
        "keys kept per replica let a retry on another replica execute twice");
    // permitido solo donde se ejecuta un único proceso (local, test): la comprobación anterior
    // falla en cualquier otro lugar, por lo que esto es información, no una advertencia
    LoggerFactory.getLogger(AxizIdempotencyAutoConfiguration.class)
        .info(
            "Idempotency keys are kept in memory (single process); replicas need redis or"
                + " dynamodb");
    return new InMemoryIdempotencyStore(clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  IdempotencyService axizIdempotencyService(
      IdempotencyStore store,
      AxizIdempotencyProperties p,
      ObjectProvider<Clock> clock,
      ObjectProvider<IdempotencyResolver> resolver) {
    return new IdempotencyService(
        store,
        p.inProgressTtl(),
        p.retention(),
        clock.getIfAvailable(Clock::systemUTC),
        resolver.getIfAvailable());
  }

  /**
   * Los handlers {@code @Idempotent} de los beans {@code @Controller} los envuelve un advisor de
   * infraestructura ordenado después de la autorización de métodos de Spring Security: toda
   * solicitud, incluidas las reproducciones, pasa primero las comprobaciones de
   * {@code @PreAuthorize}, {@code @Secured} y JSR-250.
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor axizIdempotencyAdvisor(
      ObjectProvider<IdempotencyService> service,
      ObjectProvider<AsyncIdempotencyService> asyncService,
      @Qualifier("axizIdempotencyPayloadProtector") ObjectProvider<PayloadProtector> protection,
      @Qualifier("axizIdempotencyAsyncPayloadProtector") ObjectProvider<AsyncPayloadProtector> asyncProtection) {
    ClassFilter controllers = type -> AnnotatedElementUtils.hasAnnotation(type, Controller.class);
    Pointcut onClass =
        new ComposablePointcut(new AnnotationMatchingPointcut(Idempotent.class, true))
            .intersection(controllers);
    Pointcut onMethod =
        new ComposablePointcut(new AnnotationMatchingPointcut(null, Idempotent.class, true))
            .intersection(controllers);
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new ComposablePointcut(onClass).union(onMethod),
            new IdempotencyMethodInterceptor(
                service::getObject,
                asyncService::getObject,
                protection::getObject,
                asyncProtection::getObject));
    advisor.setOrder(IdempotencyMethodInterceptor.ORDER);
    return advisor;
  }

  @Bean
  @ConditionalOnMissingBean
  IdempotencyReplayHandler axizIdempotencyReplayHandler() {
    return new IdempotencyReplayHandler();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  static class ServletConfiguration {
    @Bean
    PayloadProtector axizIdempotencyPayloadProtector(
        AxizIdempotencyProtectionProperties p, ObjectProvider<EnvelopeEncryptor> envelope) {
      return p.responseProtection().create(envelope::getObject);
    }

    @Bean
    IdempotencyFilter axizIdempotencyFilter(
        IdempotencyService service,
        AxizIdempotencyProperties p,
        ObjectProvider<HttpPayloadPolicy> policies,
        @Qualifier("axizIdempotencyPayloadProtector") PayloadProtector protection) {
      return new IdempotencyFilter(
          service,
          p.maxBodySize(),
          p.maxResponseSize(),
          policies.orderedStream().toList(),
          protection);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  static class ReactiveConfiguration {
    @Bean
    AsyncPayloadProtector axizIdempotencyAsyncPayloadProtector(
        AxizIdempotencyProtectionProperties p, ObjectProvider<AsyncEnvelopeEncryptor> envelope) {
      return p.responseProtection().createAsync(envelope::getObject);
    }

    @Bean
    @ConditionalOnMissingBean
    AsyncIdempotencyStore axizInMemoryAsyncIdempotencyStore(
        AxizIdempotencyProperties p, ObjectProvider<Clock> clock, Environment env) {
      if (!"memory".equals(p.store()))
        throw new IllegalStateException("Async idempotency store unavailable: " + p.store());
      Environments.requireSingleProcess(
          env.getProperty("axiz.config.environment"),
          "axiz.idempotency.store=memory",
          "redis or dynamodb",
          "replicas need a shared store");
      return new InMemoryAsyncIdempotencyStore(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    AsyncIdempotencyService axizAsyncIdempotencyService(
        AsyncIdempotencyStore store,
        AxizIdempotencyProperties p,
        ObjectProvider<Clock> clock,
        ObjectProvider<AsyncIdempotencyResolver> resolver) {
      return new AsyncIdempotencyService(
          store,
          p.inProgressTtl(),
          p.retention(),
          clock.getIfAvailable(Clock::systemUTC),
          resolver.getIfAvailable());
    }

    @Bean
    IdempotencyWebFilter axizIdempotencyWebFilter(
        AsyncIdempotencyService service,
        AxizIdempotencyProperties p,
        ObjectProvider<HttpPayloadPolicy> policies,
        @Qualifier("axizIdempotencyAsyncPayloadProtector") AsyncPayloadProtector protection) {
      return new IdempotencyWebFilter(
          service,
          p.maxBodySize(),
          p.maxResponseSize(),
          policies.orderedStream().toList(),
          protection);
    }
  }
}
