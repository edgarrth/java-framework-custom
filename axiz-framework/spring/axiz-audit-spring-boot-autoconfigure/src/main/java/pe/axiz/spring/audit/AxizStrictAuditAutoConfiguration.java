package pe.axiz.spring.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/** Explicit confirmed path, separate from the ordinary publisher's tolerant delivery policy. */
@AutoConfiguration(
    after = {AxizAuditAutoConfiguration.class, AxizNativeAuditAutoConfiguration.class})
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty(name = "axiz.audit.strict.enabled")
public class AxizStrictAuditAutoConfiguration {
  private static void requireSealing(AuditIntegritySupport integrity) {
    if (!integrity.enabled())
      throw new IllegalStateException("Strict audit requires integrity sealing");
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnImperativeApplication
  @ConditionalOnMissingBean(StrictAuditPublisher.class)
  StrictAuditPublisher axizStrictAuditPublisher(
      AxizAuditProperties properties,
      ObjectProvider<StrictAuditSink> sinks,
      AuditIntegritySupport integrity,
      ObjectProvider<MeterRegistry> registries,
      BeanFactory beans) {
    requireSealing(integrity);
    var publication =
        properties.strict().mode() == AxizAuditProperties.StrictMode.DURABLE
            ? StrictAuditTargets.durable(beans, properties, integrity)
            : StrictDirectTargets.imperative(sinks.orderedStream().toList(), integrity);
    return new StrictAuditPipeline.Imperative(
        new StrictAuditPipeline(properties.strict(), publication, registries.getIfAvailable()));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class NativeConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AsyncStrictAuditPublisher.class)
    AsyncStrictAuditPublisher axizAsyncStrictAuditPublisher(
        AxizAuditProperties properties,
        ObjectProvider<AsyncStrictAuditSink> sinks,
        AuditIntegritySupport integrity,
        ObjectProvider<MeterRegistry> registries,
        BeanFactory beans) {
      requireSealing(integrity);
      var publication =
          properties.strict().mode() == AxizAuditProperties.StrictMode.DURABLE
              ? ReactiveStrictAuditTargets.durable(beans, properties, integrity)
              : StrictDirectTargets.nativeTargets(sinks.orderedStream().toList(), integrity);
      return new StrictAuditPipeline(properties.strict(), publication, registries.getIfAvailable());
    }
  }
}
