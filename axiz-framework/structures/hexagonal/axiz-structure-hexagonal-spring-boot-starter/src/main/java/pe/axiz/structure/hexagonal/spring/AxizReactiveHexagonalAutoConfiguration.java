package pe.axiz.structure.hexagonal.spring;

import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import org.reactivestreams.Publisher;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.structure.hexagonal.ReactiveDomainEventSink;
import pe.axiz.structure.hexagonal.ReactiveUseCase;

/** Reactive hexagonal use cases, selected by the service execution stack. */
@AutoConfiguration(after = AxizHexagonalAutoConfiguration.class)
@ConditionalOnClass(name = "reactor.core.publisher.Mono")
public class AxizReactiveHexagonalAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(ReactiveTransactionalEvents.class)
  @ConditionalOnBean(ReactiveTransactionalEvents.class)
  static class OutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReactiveDomainEventSink.class)
    ReactiveDomainEventSink axizReactiveDomainEventSink(
        ReactiveTransactionalEvents events, AxizHexagonalProperties properties) {
      return new ReactiveOutboxDomainEventSink(
          events,
          properties.events().destination(),
          properties.events().destinations(),
          properties.events().typeSuffix());
    }
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  Advisor axizReactiveUseCaseAdvisor(
      ObjectProvider<ReactiveTransactionManager> tx,
      ObjectProvider<ObservationRegistry> observations) {
    var pointcut =
        new ComposablePointcut(AxizHexagonalAutoConfiguration.useCaseMethods())
            .intersection(type -> ReactiveUseCase.class.isAssignableFrom(type))
            .intersection(
                new StaticMethodMatcher() {
                  @Override
                  public boolean matches(Method method, Class<?> targetClass) {
                    return Publisher.class.isAssignableFrom(method.getReturnType());
                  }
                });
    var advisor =
        new DefaultPointcutAdvisor(
            pointcut,
            new ReactiveUseCaseInterceptor(
                tx::getIfAvailable, observations.getIfAvailable(() -> ObservationRegistry.NOOP)));
    advisor.setOrder(50);
    return advisor;
  }
}
