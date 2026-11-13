package pe.axiz.spring.inbox;

import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.adapter.inbox.r2dbc.R2dbcInboxStore;
import pe.axiz.adapter.inbox.r2dbc.R2dbcReactiveInboxStore;
import pe.axiz.adapter.inbox.r2dbc.ReactiveInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.spring.foundation.ReactiveInboxCompletion;
import reactor.core.publisher.Mono;

/**
 * Aurora inbox over R2DBC ({@code axiz.inbox.store=r2dbc}) for reactive services. The dispatcher
 * calls the inbox on the consumer thread, never on an event loop; the table comes from the
 * service's Flyway migrations.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.r2dbc.AxizR2dbcAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.inbox.r2dbc.R2dbcInboxStore")
@ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "r2dbc")
@ConditionalOnBooleanProperty(name = "axiz.inbox.enabled", matchIfMissing = true)
@ConditionalOnBean(ReactiveSql.class)
@EnableConfigurationProperties(AxizInboxProperties.class)
public class AxizInboxR2dbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ReactiveInboxStore axizReactiveInboxStore(ReactiveSql sql, AxizInboxProperties p) {
    return new R2dbcReactiveInboxStore(sql, p.table());
  }

  @Bean
  @Nullable ReactiveInboxCompletion axizR2dbcInboxCompletion(
      ReactiveInboxStore store, ObjectProvider<Clock> clocks) {
    Object resource = store.transactionResource();
    if (resource == null) return null;
    Clock clock = clocks.getIfAvailable(Clock::systemUTC);
    return new ReactiveInboxCompletion() {
      @Override
      public Object transactionResource() {
        return resource;
      }

      @Override
      public Mono<Boolean> complete(String consumer, String key, String owner) {
        return store.markProcessed(consumer, key, owner, clock.instant());
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveInbox axizR2dbcReactiveInbox(ReactiveInboxStore store) {
    return new R2dbcInboxFacade(store);
  }

  @Bean
  @ConditionalOnMissingBean
  InboxStore axizR2dbcInboxStore(ReactiveSql sql, AxizInboxProperties p) {
    return new R2dbcInboxStore(sql, p.table(), p.statementTimeout());
  }

  @Bean
  InboxPurger axizInboxPurger(
      InboxStore store, AxizInboxProperties p, ObjectProvider<Clock> clock) {
    return new InboxPurger(store, p.purgeInterval(), clock.getIfAvailable(Clock::systemUTC));
  }
}
