package pe.axiz.spring.audit;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import pe.axiz.core.audit.AsyncAuditPublisher;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditQueueMetrics;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.CompositeAuditPublisher;
import pe.axiz.core.audit.ConfirmedAuditPublisher;
import pe.axiz.core.audit.LoggingAuditPublisher;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.foundation.ConfigurationErrors;
import pe.axiz.spring.foundation.DispatchBoundary;

/**
 * Audit: {@link Auditable} through an infrastructure advisor, sanitized immutable events, real
 * asynchronous publication (bounded queue, virtual thread, log fallback) and optional HTTP audit.
 * Sinks come from adapter auto-configurations (MSK, SQS); without sinks events go to the log.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
      "pe.axiz.spring.sensitive.AxizSensitiveAutoConfiguration",
      "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditKafkaAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditSqsAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizAuditAutoConfiguration {

  @Bean
  @ConditionalOnImperativeApplication
  DispatchBoundary axizAuditDispatchBoundary() {
    return () -> {
      var scope = PendingAuditFailures.open();
      return scope::close;
    };
  }

  @Bean
  static BeanFactoryPostProcessor axizAuditConfigurationValidation(Environment environment) {
    return beans -> {
      if (AuditModeSelection.managed(beans)) AuditModeSelection.requireExplicit(environment);
    };
  }

  @Bean
  SmartInitializingSingleton axizAuditModeNotice(
      Environment environment,
      AxizAuditProperties properties,
      ConfigurableListableBeanFactory beans) {
    return () -> {
      boolean managed = AuditModeSelection.managed(beans);
      if (managed
          && properties.effectiveMode() == AxizAuditProperties.Mode.ASYNC
          && !AuditModeSelection.development(environment))
        LoggerFactory.getLogger(AxizAuditAutoConfiguration.class)
            .warn(
                "Audit mode async uses an in-memory queue and is not durable after a process"
                    + " crash");
    };
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.core.outbox.OutboxRelay")
  static class DurableRouteConfiguration {
    @Bean
    SmartInitializingSingleton axizDurableAuditRouteValidation(
        AxizAuditProperties properties,
        ConfigurableListableBeanFactory beans,
        Environment environment) {
      return () -> {
        if (properties.effectiveMode() != AxizAuditProperties.Mode.DURABLE
            || !AuditModeSelection.managed(beans)) return;
        var relay = beans.getBeanProvider(OutboxRelay.class).getIfAvailable();
        // A writer-only service must explicitly disable its local relay.
        if (!environment.getProperty("axiz.outbox.relay.enabled", Boolean.class, true)) return;
        if (relay == null)
          throw new IllegalStateException(
              "DURABLE audit requires an outbox relay publisher; configure messaging or explicitly"
                  + " disable axiz.outbox.relay.enabled");
        if (!relay.supportsDestination(properties.durable().destination()))
          throw new IllegalStateException(
              unsupportedDestination(properties.durable().destination(), environment));
      };
    }
  }

  private static String unsupportedDestination(String logical, Environment environment) {
    String key = ConfigurationErrors.destinationProperty("axiz.messaging.destinations", logical);
    String resolved = logical;
    if (environment instanceof ConfigurableEnvironment configurable) {
      var resolver = new PropertySourcesPropertyResolver(configurable.getPropertySources());
      resolver.setIgnoreUnresolvableNestedPlaceholders(true);
      // Bind the same scalar map as messaging: both dotted and bracketed keys are legal.
      var binder =
          new Binder(
              ConfigurationPropertySources.get(configurable),
              value -> value instanceof String text ? resolver.resolvePlaceholders(text) : value);
      Map<String, String> destinations =
          Objects.requireNonNull(
              binder
                  .bind("axiz.messaging.destinations", Bindable.mapOf(String.class, String.class))
                  .orElse(Map.of()));
      String mapped = destinations.get(logical);
      resolved = mapped == null ? logical : mapped;
    }
    return "DURABLE audit "
        + ConfigurationErrors.destination(logical, resolved)
        + " and is unsupported by the outbox relay publisher; configure "
        + key;
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  AuditPublisher axizAuditPublisher(
      AxizAuditProperties properties, ObjectProvider<AuditSink> sinks, BeanFactory beans) {
    List<AuditPublisher> delegates = sinks.orderedStream().map(AuditSink::publisher).toList();
    LoggingAuditPublisher log = new LoggingAuditPublisher();
    AuditPublisher target =
        delegates.isEmpty()
            ? log
            : delegates.size() == 1 ? delegates.get(0) : new CompositeAuditPublisher(delegates);
    AuditPublisher delivery =
        properties.effectiveMode() != AxizAuditProperties.Mode.SYNC
            ? new AsyncAuditPublisher(
                target,
                log,
                properties.async().capacity(),
                properties.async().offerTimeout(),
                Duration.ofSeconds(10),
                properties.async().deliveryTimeout(),
                AuditDeliveryContext.PROVIDER)
            : target;
    if (properties.effectiveMode() == AxizAuditProperties.Mode.DURABLE) {
      try {
        return DurableAudit.wrap(beans, properties.durable(), delivery);
      } catch (RuntimeException | Error failure) {
        if (delivery instanceof AsyncAuditPublisher async) async.close();
        throw failure;
      }
    }
    return properties.effectiveMode() == AxizAuditProperties.Mode.SYNC
        ? new ConfirmedAuditPublisher(target)
        : delivery;
  }

  /**
   * Health of the asynchronous publisher as metrics: {@code axiz.audit.pending}, {@code
   * axiz.audit.lost} (alert when it grows), {@code axiz.audit.fallback}, {@code
   * axiz.audit.failures} and {@code axiz.audit.worker.alive} (1 or 0).
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnImperativeApplication
  @ConditionalOnClass(name = "org.springframework.transaction.interceptor.TransactionInterceptor")
  static AuditAdvisorOrderValidator axizAuditAdvisorOrder(ConfigurableListableBeanFactory beans) {
    return new AuditAdvisorOrderValidator(beans);
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnImperativeApplication
  @ConditionalOnClass(
      name = "org.springframework.transaction.support.TransactionSynchronizationManager")
  static Advisor axizDurableAuditSuccessAdvisor(ObjectProvider<AxizAuditProperties> properties) {
    var advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Auditable.class, true),
            new TransactionalAuditSuccessInterceptor(
                () -> properties.getObject().effectiveMode() == AxizAuditProperties.Mode.DURABLE));
    advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
    return advisor;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class MetricsConfiguration {

    @Bean
    MeterBinder axizAuditMetrics(ObjectProvider<AuditPublisher> publisher) {
      return registry -> {
        AuditPublisher actual = publisher.getIfAvailable();
        if (actual instanceof TransactionalAuditPublisher durable)
          FunctionCounter.builder(
                  "axiz.audit.persistence.not.confirmed",
                  durable,
                  TransactionalAuditPublisher::notConfirmedCount)
              .description("Durable audit writes degraded to the asynchronous fallback")
              .register(registry);
        if (actual instanceof ConfirmedAuditPublisher sync)
          FunctionCounter.builder(
                  "axiz.audit.persistence.unconfirmed",
                  sync,
                  ConfirmedAuditPublisher::unconfirmedCount)
              .description("Destination acknowledgements that failed after business completion")
              .register(registry);
        bindQueue(registry, asyncOf(actual));
      };
    }

    static void bindQueue(MeterRegistry registry, @Nullable AuditQueueMetrics async) {
      if (async == null) {
        return;
      }
      Gauge.builder("axiz.audit.pending", async, AuditQueueMetrics::pending)
          .description("Audit events waiting in the queue")
          .register(registry);
      FunctionCounter.builder("axiz.audit.lost", async, AuditQueueMetrics::lostCount)
          .description("Audit events lost: delegate and fallback both failed")
          .register(registry);
      FunctionCounter.builder("axiz.audit.unconfirmed", async, AuditQueueMetrics::unconfirmedCount)
          .description("Audit fallback acceptances without confirmation")
          .register(registry);
      FunctionCounter.builder("axiz.audit.fallback", async, AuditQueueMetrics::fallbackCount)
          .description("Audit events written to the fallback publisher")
          .register(registry);
      FunctionCounter.builder("axiz.audit.failures", async, AuditQueueMetrics::failureCount)
          .description("Audit deliveries that failed")
          .register(registry);
      Gauge.builder("axiz.audit.worker.alive", async, a -> a.workerAlive() ? 1 : 0)
          .description("1 while the audit worker is draining the queue")
          .register(registry);
    }

    static @Nullable AsyncAuditPublisher asyncOf(@Nullable AuditPublisher publisher) {
      if (publisher instanceof AsyncAuditPublisher async) {
        return async;
      }
      if (publisher instanceof TransactionalAuditPublisher durable) {
        return asyncOf(durable.fallback());
      }
      return null;
    }
  }

  @Bean
  @ConditionalOnMissingBean
  AuditContextResolver axizAuditContextResolver() {
    return AuditContextResolver.fromRequestContext();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  AuditTrail axizAuditTrail(
      Environment env,
      AuditPublisher publisher,
      ObjectProvider<ObjectMasker> masker,
      ObjectProvider<IdGenerator> ids,
      ObjectProvider<Clock> clock) {
    return new AuditTrail(
        env.getProperty("spring.application.name", "application"),
        publisher,
        masker.getIfAvailable(ObjectMasker::defaults),
        ids.getIfAvailable(IdGenerator::uuidV7),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnImperativeApplication
  static Advisor axizAuditableAdvisor(
      ObjectProvider<AuditTrail> trail, ObjectProvider<AuditContextResolver> context) {
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Auditable.class, true),
            new AuditableInterceptor(
                trail::getObject,
                () -> context.getIfAvailable(AuditContextResolver::fromRequestContext)));
    advisor.setOrder(20);
    return advisor;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  @ConditionalOnBooleanProperty(name = "axiz.audit.http.enabled")
  static class ServletConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HttpAuditFilter axizHttpAuditFilter(
        AuditTrail trail, AuditContextResolver context, AxizAuditProperties properties) {
      return new HttpAuditFilter(trail, context, properties.http());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  @ConditionalOnBooleanProperty(name = "axiz.audit.http.enabled")
  static class ReactiveConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HttpAuditWebFilter axizHttpAuditWebFilter(
        ReactiveAuditTrail trail, AuditContextResolver context, AxizAuditProperties properties) {
      return new HttpAuditWebFilter(trail, context, properties.http());
    }
  }
}
