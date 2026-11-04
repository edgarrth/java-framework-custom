package pe.axiz.spring.audit;

import java.time.Clock;
import java.util.List;
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
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.CompositeAsyncAuditPublisher;
import pe.axiz.core.audit.LoggingAsyncAuditPublisher;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;

/** Native audit wiring for reactive applications; never adapts an imperative sink. */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.audit.AxizAuditAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditSqsAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditKafkaAutoConfiguration"
    })
@ConditionalOnClass(name = "reactor.core.publisher.Mono")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizNativeAuditAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  AsyncAuditEventPublisher axizAsyncAuditEventPublisher(
      ObjectProvider<AsyncAuditSink> sinks,
      ObjectProvider<AuditSink> imperative,
      AxizAuditProperties properties) {
    if (imperative.stream().findAny().isPresent())
      throw new IllegalStateException(
          "Reactive audit requires AsyncAuditSink; imperative AuditSink is not supported");
    if (properties.kafka().topic() != null
        && sinks.stream().noneMatch(sink -> sink.name().equals("kafka")))
      throw new IllegalStateException(
          "Reactive Kafka audit requires a native producer configuration; imperative Kafka audit is not supported");
    List<AsyncAuditEventPublisher> targets =
        sinks.orderedStream().map(AsyncAuditSink::publisher).toList();
    return targets.isEmpty()
        ? new LoggingAsyncAuditPublisher()
        : targets.size() == 1 ? targets.getFirst() : new CompositeAsyncAuditPublisher(targets);
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveAuditPublisher axizReactiveAuditPublisher(
      AsyncAuditEventPublisher publisher, AxizAuditProperties properties, BeanFactory beans) {
    ReactiveAuditPublisher fallback = ReactiveAuditPublisher.from(publisher);
    return properties.durable().enabled()
        ? ReactiveDurableAudit.wrap(beans, properties.durable(), fallback)
        : fallback;
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
  static Advisor axizReactiveAuditableAdvisor(
      ObjectProvider<ReactiveAuditTrail> trail, ObjectProvider<AuditContextResolver> context) {
    var advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Auditable.class, true),
            new ReactiveAuditableInterceptor(
                trail::getObject,
                () -> context.getIfAvailable(AuditContextResolver::fromRequestContext)));
    // Hexagonal use-case transaction advisor is 50; audit must execute inside that transaction.
    advisor.setOrder(60);
    return advisor;
  }
}
