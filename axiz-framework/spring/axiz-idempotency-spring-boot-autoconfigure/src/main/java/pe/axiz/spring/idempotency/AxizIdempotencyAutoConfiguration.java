package pe.axiz.spring.idempotency;

import java.time.Clock;
import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.Pointcut;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;

/**
 * {@code @Idempotent} operations with the {@code Idempotency-Key} header: responses stored in
 * ElastiCache (Redis) or DynamoDB and replayed; 400, 409 and 422 on misuse.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.idempotency.AxizIdempotencyRedisAutoConfiguration",
      "pe.axiz.spring.idempotency.AxizIdempotencyDynamoDbAutoConfiguration"
    })
@ConditionalOnWebApplication
@ConditionalOnBooleanProperty(name = "axiz.idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizIdempotencyProperties.class)
public class AxizIdempotencyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  IdempotencyStore axizInMemoryIdempotencyStore(
      AxizIdempotencyProperties p, ObjectProvider<Clock> clock, Environment env) {
    if (!"memory".equals(p.store())) {
      throw new IllegalStateException(
          "axiz.idempotency.store="
              + p.store()
              + " is not available: add its client and connection settings");
    }
    Environments.requireSingleProcess(
        env.getProperty("axiz.config.environment", "local"),
        "axiz.idempotency.store=memory",
        "redis or dynamodb",
        "keys kept per replica let a retry on another replica execute twice");
    org.slf4j.LoggerFactory.getLogger(AxizIdempotencyAutoConfiguration.class)
        .warn(
            "Idempotency keys are kept in memory: use redis or dynamodb with more than one"
                + " replica");
    return new InMemoryIdempotencyStore(clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  IdempotencyService axizIdempotencyService(
      IdempotencyStore store, AxizIdempotencyProperties p, ObjectProvider<Clock> clock) {
    return new IdempotencyService(
        store, p.inProgressTtl(), p.retention(), clock.getIfAvailable(Clock::systemUTC));
  }

  /**
   * {@code @Idempotent} handlers of {@code @Controller} beans are wrapped by an infrastructure
   * advisor ordered after Spring Security's method authorization: every request, replays included,
   * passes {@code @PreAuthorize}, {@code @Secured} and JSR-250 checks first.
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor axizIdempotencyAdvisor(ObjectProvider<IdempotencyService> service) {
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
            new IdempotencyMethodInterceptor(service::getObject));
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
    IdempotencyFilter axizIdempotencyFilter(
        IdempotencyService service, AxizIdempotencyProperties p) {
      return new IdempotencyFilter(service, p.maxBodySize(), p.maxResponseSize());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  static class ReactiveConfiguration {

    @Bean
    IdempotencyWebFilter axizIdempotencyWebFilter(
        IdempotencyService service, AxizIdempotencyProperties p) {
      return new IdempotencyWebFilter(service, p.maxBodySize(), p.maxResponseSize());
    }
  }
}
