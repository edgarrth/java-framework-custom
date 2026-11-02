package pe.axiz.structure.eda.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.messaging.EventRoutes;
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
public class AxizEdaAutoConfiguration {

  /** Choreography over the R2DBC outbox. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter")
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
  @ConditionalOnBean(TransactionalEvents.class)
  Reactions axizReactions(TransactionalEvents events, EventRoutes routes) {
    return new Reactions(events, routes);
  }
}
