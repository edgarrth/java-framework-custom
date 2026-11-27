package pe.axiz.structure.hexagonal.spring;

import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcher;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AttributeSanitizer;
import pe.axiz.spring.outbox.TransactionalEvents;
import pe.axiz.structure.hexagonal.DomainEventSink;
import pe.axiz.structure.hexagonal.ReactiveUseCase;
import pe.axiz.structure.hexagonal.UseCaseRunner;
import pe.axiz.structure.hexagonal.UseCaseTransaction;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/**
 * Estilo hexagonal: las clases {@code @UseCase} se ejecutan en una transacción con una observación
 * y publican sus eventos de dominio en el commit, mediante el outbox cuando el servicio tiene uno.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.outbox.AxizOutboxAutoConfiguration",
      "pe.axiz.spring.observability.AxizObservabilityAutoConfiguration",
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration"
    })
@EnableConfigurationProperties(AxizHexagonalProperties.class)
public class AxizHexagonalAutoConfiguration {

  /** Publicación mediante el outbox cuando el starter de outbox está presente. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.spring.outbox.TransactionalEvents")
  @ConditionalOnBean(type = "pe.axiz.spring.outbox.TransactionalEvents")
  static class OutboxConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DomainEventSink axizOutboxDomainEventSink(
        TransactionalEvents events, AxizHexagonalProperties p) {
      return new OutboxDomainEventSink(
          events, p.events().destination(), p.events().destinations(), p.events().typeSuffix());
    }
  }

  @Bean
  @ConditionalOnMissingBean
  DomainEventSink axizApplicationEventDomainEventSink(ApplicationEventPublisher publisher) {
    return new ApplicationEventDomainEventSink(publisher);
  }

  @Bean
  @ConditionalOnMissingBean
  UseCaseRunner axizUseCaseRunner(
      ObjectProvider<PlatformTransactionManager> tx, DomainEventSink sink) {
    PlatformTransactionManager manager = tx.getIfAvailable();
    UseCaseTransaction transaction =
        manager != null ? new SpringUseCaseTransaction(manager) : UseCaseTransaction.none();
    return new UseCaseRunner(transaction, sink);
  }

  /**
   * Métodos de negocio públicos de las clases {@code @UseCase} (nunca los métodos de {@code
   * Object}).
   */
  static Pointcut useCaseMethods() {
    return new ComposablePointcut(
            new AnnotationMatchingPointcut(UseCase.class, true).getClassFilter())
        .intersection(
            new StaticMethodMatcher() {
              @Override
              public boolean matches(Method method, Class<?> targetClass) {
                return Modifier.isPublic(method.getModifiers())
                    && method.getDeclaringClass() != Object.class;
              }
            });
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor axizUseCaseAdvisor(
      ObjectProvider<UseCaseRunner> runner,
      ObjectProvider<ArchitectureObservation> observation,
      ObjectProvider<ObservationRegistry> registry) {
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new ComposablePointcut(useCaseMethods())
                .intersection(type -> !ReactiveUseCase.class.isAssignableFrom(type)),
            new UseCaseInterceptor(
                runner::getObject,
                () ->
                    observation.getIfAvailable(
                        () ->
                            new ArchitectureObservation(
                                registry.getIfAvailable(() -> ObservationRegistry.NOOP),
                                t -> false,
                                AttributeSanitizer.defaults()))));
    advisor.setOrder(50);
    return advisor;
  }
}
