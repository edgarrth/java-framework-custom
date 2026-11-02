package pe.axiz.spring.audit;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.BeanFactory;
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
import pe.axiz.core.audit.AsyncAuditPublisher;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.CompositeAuditPublisher;
import pe.axiz.core.audit.LoggingAuditPublisher;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Audit: {@link Auditable} through an infrastructure advisor, sanitized immutable events, real
 * asynchronous publication (bounded queue, virtual thread, log fallback) and optional HTTP audit.
 * Sinks come from adapter auto-configurations (MSK, SQS); without sinks events go to the log.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.sensitive.AxizSensitiveAutoConfiguration",
      "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditKafkaAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditSqsAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizAuditAutoConfiguration {

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
        properties.async().enabled()
            ? new AsyncAuditPublisher(
                target, log, properties.async().capacity(), properties.async().offerTimeout())
            : target;
    return properties.durable().enabled()
        ? DurableAudit.wrap(beans, properties.durable(), delivery)
        : delivery;
  }

  /**
   * Health of the asynchronous publisher as metrics: {@code axiz.audit.pending}, {@code
   * axiz.audit.lost} (alert when it grows), {@code axiz.audit.fallback}, {@code
   * axiz.audit.failures} and {@code axiz.audit.worker.alive} (1 or 0).
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class MetricsConfiguration {

    @Bean
    MeterBinder axizAuditMetrics(ObjectProvider<AuditPublisher> publisher) {
      return registry -> {
        AsyncAuditPublisher async = asyncOf(publisher.getIfAvailable());
        if (async == null) {
          return;
        }
        Gauge.builder("axiz.audit.pending", async, AsyncAuditPublisher::pending)
            .description("Audit events waiting in the queue")
            .register(registry);
        FunctionCounter.builder("axiz.audit.lost", async, AsyncAuditPublisher::lostCount)
            .description("Audit events lost: delegate and fallback both failed")
            .register(registry);
        FunctionCounter.builder("axiz.audit.fallback", async, AsyncAuditPublisher::fallbackCount)
            .description("Audit events written to the fallback publisher")
            .register(registry);
        FunctionCounter.builder("axiz.audit.failures", async, AsyncAuditPublisher::failureCount)
            .description("Audit deliveries that failed")
            .register(registry);
        Gauge.builder("axiz.audit.worker.alive", async, a -> a.workerAlive() ? 1 : 0)
            .description("1 while the audit worker is draining the queue")
            .register(registry);
      };
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
