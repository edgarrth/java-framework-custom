package pe.axiz.spring.audit;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditIntegrityMetrics;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.CompositeAsyncAuditPublisher;
import pe.axiz.core.audit.ConfirmedAsyncAuditPublisher;
import pe.axiz.core.audit.LoggingAsyncAuditPublisher;
import pe.axiz.core.audit.LoggingAuditPublisher;
import pe.axiz.core.audit.QueuedAsyncAuditPublisher;
import pe.axiz.core.audit.SealingAsyncAuditPublisher;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.spring.foundation.ReactiveDispatchBoundary;

/** Cableado de auditoría nativa para aplicaciones reactivas; nunca adapta un destino imperativo. */
@AutoConfiguration(afterName = {"pe.axiz.spring.audit.AxizAuditAutoConfiguration"})
@ConditionalOnClass(name = "reactor.core.publisher.Mono")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizNativeAuditAutoConfiguration {
  @Bean
  ReactiveDispatchBoundary axizReactiveAuditDispatchBoundary() {
    return ReactivePendingAuditFailures::boundary;
  }

  @Bean
  @ConditionalOnMissingBean
  AsyncAuditEventPublisher axizAsyncAuditEventPublisher(
      ObjectProvider<AsyncAuditSink> sinks,
      ObjectProvider<AuditSink> imperative,
      AxizAuditProperties properties,
      AuditIntegritySupport integrity) {
    if (imperative.stream().findAny().isPresent())
      throw new IllegalStateException(
          "Reactive audit requires AsyncAuditSink; imperative AuditSink is not supported");
    List<AsyncAuditEventPublisher> targets =
        sinks.orderedStream().map(AsyncAuditSink::publisher).toList();
    AsyncAuditEventPublisher target =
        targets.isEmpty()
            ? new LoggingAsyncAuditPublisher()
            : targets.size() == 1 ? targets.getFirst() : new CompositeAsyncAuditPublisher(targets);
    AsyncAuditEventPublisher delivery =
        properties.effectiveMode() == AxizAuditProperties.Mode.SYNC
            ? new ConfirmedAsyncAuditPublisher(target)
            : new QueuedAsyncAuditPublisher(
                target,
                new LoggingAuditPublisher(),
                properties.async().capacity(),
                properties.async().deliveryTimeout(),
                AuditDeliveryContext.PROVIDER);
    return properties.effectiveMode() == AxizAuditProperties.Mode.DURABLE
        ? delivery
        : new SealingAsyncAuditPublisher(
            integrity.memory(properties.effectiveMode().name().toLowerCase(Locale.ROOT)), delivery);
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveAuditPublisher axizReactiveAuditPublisher(
      AsyncAuditEventPublisher publisher,
      AxizAuditProperties properties,
      BeanFactory beans,
      AuditIntegritySupport integrity) {
    ReactiveAuditPublisher fallback = ReactiveAuditPublisher.from(publisher);
    return properties.effectiveMode() == AxizAuditProperties.Mode.DURABLE
        ? ReactiveDurableAudit.wrap(beans, properties.durable(), fallback, integrity)
        : fallback;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class MetricsConfiguration {
    @Bean
    MeterBinder axizNativeAuditMetrics(
        AsyncAuditEventPublisher delivery, ReactiveAuditPublisher publisher) {
      return registry -> {
        AxizAuditAutoConfiguration.MetricsConfiguration.bindIntegrity(
            registry,
            publisher instanceof AuditIntegrityMetrics metrics
                ? metrics
                : AuditPublisherLookup.find(delivery, AuditIntegrityMetrics.class));
        var queued = AuditPublisherLookup.find(delivery, QueuedAsyncAuditPublisher.class);
        if (queued != null)
          AxizAuditAutoConfiguration.MetricsConfiguration.bindQueue(registry, queued);
        var sync = AuditPublisherLookup.find(delivery, ConfirmedAsyncAuditPublisher.class);
        if (sync != null)
          FunctionCounter.builder(
                  "axiz.audit.persistence.unconfirmed",
                  sync,
                  ConfirmedAsyncAuditPublisher::unconfirmedCount)
              .description("Destination acknowledgements that failed after business completion")
              .register(registry);
        if (publisher instanceof ReactiveTransactionalAuditPublisher durable)
          FunctionCounter.builder(
                  "axiz.audit.persistence.not.confirmed",
                  durable,
                  ReactiveTransactionalAuditPublisher::notConfirmedCount)
              .description("Durable audit writes degraded to the asynchronous fallback")
              .register(registry);
      };
    }
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveAuditTrail axizReactiveAuditTrail(
      Environment env,
      ReactiveAuditPublisher publisher,
      ObjectProvider<ObjectMasker> masker,
      ObjectProvider<IdGenerator> ids,
      ObjectProvider<Clock> clock) {
    return new ReactiveAuditTrail(
        env.getProperty("spring.application.name", "application"),
        publisher,
        masker.getIfAvailable(ObjectMasker::defaults),
        ids.getIfAvailable(IdGenerator::uuidV7),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnClass(
      name = "org.springframework.transaction.reactive.TransactionSynchronizationManager")
  static Advisor axizReactiveAuditSuccessAdvisor(ObjectProvider<AxizAuditProperties> properties) {
    var advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Auditable.class, true),
            new ReactiveTransactionalAuditSuccessInterceptor(
                () -> properties.getObject().effectiveMode() == AxizAuditProperties.Mode.DURABLE));
    advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
    return advisor;
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnClass(name = "org.springframework.transaction.interceptor.TransactionInterceptor")
  static AuditAdvisorOrderValidator axizReactiveAuditAdvisorOrder(
      ConfigurableListableBeanFactory beans) {
    return new AuditAdvisorOrderValidator(beans);
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor axizReactiveAuditableAdvisor(
      ObjectProvider<ReactiveAuditTrail> trail, ObjectProvider<AuditContextResolver> context) {
    var advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Auditable.class, true),
            new ReactiveAuditableInterceptor(
                trail::getObject,
                () -> context.getIfAvailable(AuditContextResolver::fromRequestContext)));
    advisor.setOrder(20);
    return advisor;
  }
}
