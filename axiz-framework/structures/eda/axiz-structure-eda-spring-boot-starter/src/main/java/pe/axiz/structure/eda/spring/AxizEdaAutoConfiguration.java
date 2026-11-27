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
 * Estilo orientado a eventos: handlers transaccionales (las escrituras de negocio, el inbox y el
 * outbox hacen commit juntos) y coreografía mediante {@link Reactions} o, para servicios reactivos,
 * handlers no bloqueantes en una transacción reactiva con {@link ReactiveReactions}. El consumo,
 * los reintentos y la DLQ provienen del starter de mensajería; la deduplicación, del starter de
 * inbox.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.outbox.AxizOutboxAutoConfiguration",
      "pe.axiz.spring.outbox.r2dbc.AxizOutboxR2dbcAutoConfiguration"
    })
public class AxizEdaAutoConfiguration {

  /** Coreografía sobre el outbox R2DBC. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.spring.outbox.ReactiveTransactionalEvents")
  static class ReactiveReactionsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveTransactionalEvents.class)
    ReactiveReactions axizReactiveReactions(
        ReactiveTransactionalEvents events, EventRoutes routes) {
      return new ReactiveReactions(events, routes);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.spring.outbox.TransactionalEvents")
  static class ImperativeReactionsConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TransactionalEvents.class)
    Reactions axizReactions(TransactionalEvents events, EventRoutes routes) {
      return new Reactions(events, routes);
    }
  }
}
