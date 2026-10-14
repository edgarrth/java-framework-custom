package pe.axiz.spring.inbox;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.r2dbc.R2dbcInboxStore;
import pe.axiz.core.persistence.r2dbc.ReactiveSql;

/**
 * Aurora inbox over R2DBC ({@code axiz.inbox.store=r2dbc}) for reactive services. The dispatcher
 * calls the inbox on the consumer thread, never on an event loop; the table comes from the
 * service's Flyway migrations.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.r2dbc.AxizR2dbcAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.core.inbox.r2dbc.R2dbcInboxStore")
@ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "r2dbc")
@ConditionalOnBean(ReactiveSql.class)
@EnableConfigurationProperties(AxizInboxProperties.class)
public class AxizInboxR2dbcAutoConfiguration {

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
