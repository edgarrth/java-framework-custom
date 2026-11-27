package pe.axiz.spring.sequence;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Secuencias de negocio atómicas sobre Aurora (tabla de contadores) o DynamoDB (contador atómico).
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.sequence.jdbc.AxizSequenceJdbcAutoConfiguration",
      "pe.axiz.spring.sequence.r2dbc.AxizSequenceR2dbcAutoConfiguration",
      "pe.axiz.spring.sequence.dynamodb.AxizSequenceDynamoDbAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.sequence.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSequenceProperties.class)
public class AxizSequenceAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  Sequences axizSequences(
      AxizSequenceProperties p, SequenceStore store, ObjectProvider<Clock> clock) {
    return new Sequences(p, store, clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ReactiveSequences axizReactiveSequences(
        AxizSequenceProperties p,
        ObjectProvider<AsyncSequenceStore> stores,
        ObjectProvider<Clock> clock) {
      AsyncSequenceStore store = stores.getIfAvailable();
      if (store == null)
        throw new IllegalStateException(
            "Native asynchronous sequence store unavailable: " + p.store());
      return new ReactiveSequences(p, store, clock.getIfAvailable(Clock::systemUTC));
    }
  }
}
