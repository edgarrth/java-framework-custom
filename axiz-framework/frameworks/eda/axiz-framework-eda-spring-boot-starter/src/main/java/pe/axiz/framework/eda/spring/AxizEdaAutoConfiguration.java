package pe.axiz.framework.eda.spring;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.framework.eda.EventRoutes;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.spring.outbox.TransactionalEvents;

/**
 * Event-driven style: transactional handlers (business writes, inbox and outbox commit together)
 * and choreography through {@link Reactions}, or for reactive services non-blocking handlers in a
 * reactive transaction with {@link ReactiveReactions}. Consumption, retries and DLQ come from the
 * messaging starter; de-duplication from the inbox starter.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.outbox.AxizOutboxAutoConfiguration",
      "pe.axiz.spring.outbox.AxizOutboxR2dbcAutoConfiguration"
    })
@EnableConfigurationProperties(AxizEdaProperties.class)
public class AxizEdaAutoConfiguration {

  @Bean
  @ConditionalOnBooleanProperty(name = "axiz.eda.transactional", matchIfMissing = true)
  static TransactionalEventHandlerPostProcessor axizTransactionalEventHandlers(
      ObjectProvider<PlatformTransactionManager> tx) {
    return new TransactionalEventHandlerPostProcessor(tx);
  }

  /** Reactive handlers: transaction, context and timeout (reactive services). */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  static class ReactiveConfiguration {

    @Bean
    static ReactiveEventHandlerPostProcessor axizReactiveEventHandlers(
        ObjectProvider<ReactiveTransactionManager> tx, Environment env) {
      // a static post-processor cannot take @ConfigurationProperties: bind the values with Spring
      // Boot's conversions ("100ms", "30s", relaxed names). Environment.getProperty(Duration) only
      // works when the environment carries Boot's conversion service, not in every context
      Binder binder = Binder.get(env);
      return new ReactiveEventHandlerPostProcessor(
          tx,
          binder.bind("axiz.eda.transactional", Boolean.class).orElseGet(() -> true),
          binder
              .bind("axiz.eda.handler-timeout", Duration.class)
              .orElseGet(() -> Duration.ofSeconds(30)));
    }
  }

  /** Choreography over the R2DBC outbox. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.core.outbox.r2dbc.ReactiveOutboxWriter")
  static class ReactiveReactionsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveTransactionalEvents.class)
    ReactiveReactions axizReactiveReactions(
        ReactiveTransactionalEvents events, EventRoutes routes) {
      return new ReactiveReactions(events, routes);
    }
  }

  @Bean
  @ConditionalOnMissingBean
  EventRoutes axizEventRoutes(AxizEdaProperties p) {
    return new EventRoutes(p.destination(), p.routes());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(TransactionalEvents.class)
  Reactions axizReactions(TransactionalEvents events, EventRoutes routes) {
    return new Reactions(events, routes);
  }
}
