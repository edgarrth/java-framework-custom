package pe.axiz.spring.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Registers checkpoints only when a confirmed audit path exists, including a custom strict port.
 */
@AutoConfiguration(after = AxizStrictAuditAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@ConditionalOnBean(AuditIntegritySupport.class)
public class AxizAuditCheckpointAutoConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnImperativeApplication
  @ConditionalOnBean(StrictAuditPublisher.class)
  @ConditionalOnMissingBean(AuditMemoryCheckpoints.class)
  AuditMemoryCheckpoints axizAuditMemoryCheckpoints(
      AuditIntegritySupport integrity,
      AxizAuditProperties properties,
      StrictAuditPublisher publisher,
      ObjectProvider<MeterRegistry> registries) {
    return new AuditMemoryCheckpoints(
        integrity,
        properties.strict(),
        event -> {
          publisher.publish(event);
          return CompletableFuture.completedFuture(null);
        },
        registries.getIfAvailable());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class NativeConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnBean(AsyncStrictAuditPublisher.class)
    @ConditionalOnMissingBean(AuditMemoryCheckpoints.class)
    AuditMemoryCheckpoints axizAsyncAuditMemoryCheckpoints(
        AuditIntegritySupport integrity,
        AxizAuditProperties properties,
        AsyncStrictAuditPublisher publisher,
        ObjectProvider<MeterRegistry> registries) {
      return new AuditMemoryCheckpoints(
          integrity, properties.strict(), publisher::publish, registries.getIfAvailable());
    }
  }
}
