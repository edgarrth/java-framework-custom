package pe.axiz.spring.audit;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import pe.axiz.core.audit.AsyncAuditPublisher;
import pe.axiz.core.audit.AuditIntegrityMetrics;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditQueueMetrics;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.CompositeAuditPublisher;
import pe.axiz.core.audit.ConfirmedAuditPublisher;
import pe.axiz.core.audit.LoggingAuditPublisher;
import pe.axiz.core.audit.SealingAuditPublisher;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.foundation.ConfigurationErrors;
import pe.axiz.spring.foundation.DispatchBoundary;

/**
 * Auditoría: {@link Auditable} mediante un advisor de infraestructura, eventos inmutables saneados,
 * publicación asíncrona real (cola acotada, hilo virtual, respaldo en log) y auditoría HTTP
 * opcional. Los destinos provienen de las autoconfiguraciones de adaptadores (MSK, SQS); sin
 * destinos, los eventos van al log.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
      "pe.axiz.spring.sensitive.AxizSensitiveAutoConfiguration",
      "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizAuditAutoConfiguration {

  @Bean
  @Lazy
  AuditIntegritySupport axizAuditIntegritySupport(
      AxizAuditProperties properties,
      Environment environment,
      BeanFactory beans,
      ObjectProvider<Clock> clocks) {
    return new AuditIntegritySupport(
        properties.integrity(), environment, beans, clocks.getIfAvailable(Clock::systemUTC));
  }

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
        boolean durable =
            properties.effectiveMode() == AxizAuditProperties.Mode.DURABLE
                && AuditModeSelection.managed(beans);
        boolean strict =
            properties.strict().enabled()
                && properties.strict().mode() == AxizAuditProperties.StrictMode.DURABLE
                && AuditModeSelection.strictManaged(beans);
        if (!durable && !strict) return;
        var relay = beans.getBeanProvider(OutboxRelay.class).getIfAvailable();
        // Un servicio que solo escribe debe desactivar explícitamente su relay local.
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
      // Se enlaza el mismo mapa escalar que en mensajería: las claves con puntos y con corchetes
      // son válidas.
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
      AxizAuditProperties properties,
      ObjectProvider<AuditSink> sinks,
      BeanFactory beans,
      AuditIntegritySupport integrity) {
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
        return DurableAudit.wrap(beans, properties.durable(), delivery, integrity);
      } catch (RuntimeException | Error failure) {
        if (delivery instanceof AsyncAuditPublisher async) async.close();
        throw failure;
      }
    }
    return new SealingAuditPublisher(
        integrity.memory(properties.effectiveMode().name().toLowerCase(Locale.ROOT)),
        properties.effectiveMode() == AxizAuditProperties.Mode.SYNC
            ? new ConfirmedAuditPublisher(target)
            : delivery);
  }

  /**
   * Salud del publicador asíncrono como métricas: {@code axiz.audit.pending}, {@code
   * axiz.audit.lost} (alertar cuando crece), {@code axiz.audit.fallback}, {@code
   * axiz.audit.failures} y {@code axiz.audit.worker.alive} (1 o 0).
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
        ConfirmedAuditPublisher sync =
            AuditPublisherLookup.find(actual, ConfirmedAuditPublisher.class);
        if (sync != null)
          FunctionCounter.builder(
                  "axiz.audit.persistence.unconfirmed",
                  sync,
                  ConfirmedAuditPublisher::unconfirmedCount)
              .description("Destination acknowledgements that failed after business completion")
              .register(registry);
        bindQueue(registry, asyncOf(actual));
        bindIntegrity(registry, AuditPublisherLookup.find(actual, AuditIntegrityMetrics.class));
      };
    }

    static void bindIntegrity(MeterRegistry registry, @Nullable AuditIntegrityMetrics metrics) {
      if (metrics == null) return;
      FunctionCounter.builder(
              "axiz.audit.details.sanitized", metrics, AuditIntegrityMetrics::detailsSanitizedCount)
          .description("Audit detail substitutions and omissions counted at admission")
          .register(registry);
      for (String lane : metrics.chainSequences().keySet()) {
        Gauge.builder(
                "axiz.audit.chain.sequence", metrics, value -> value.chainSequences().get(lane))
            .tag("lane", lane)
            .description("Highest locally assigned sequence; not a committed checkpoint")
            .register(registry);
      }
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
      return AuditPublisherLookup.find(publisher, AsyncAuditPublisher.class);
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
