package pe.axiz.spring.inbox.jdbc;

import java.time.Clock;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.spring.foundation.InboxCompletion;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;
import pe.axiz.spring.inbox.AxizInboxProperties;
import pe.axiz.spring.inbox.InboxPurger;

/** JDBC inbox resources; common policies remain in the base capability. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.inbox.AxizInboxAutoConfiguration",
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.inbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizInboxJdbcProperties.class, AxizInboxProperties.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "jdbc", matchIfMissing = true)
public class AxizInboxJdbcAutoConfiguration {

  @Bean
  SchemaInitializer axizInboxSchema(DataSource dataSource, AxizInboxJdbcProperties p) {
    return new SchemaInitializer(dataSource, "inbox", p.initializeSchema());
  }

  @Bean
  @ConditionalOnMissingBean
  InboxStore axizInboxStore(
      DataSource dataSource, AxizInboxJdbcProperties p, SchemaInitializer axizInboxSchema) {
    return new JdbcInboxStore(new SpringConnectionProvider(dataSource), p.table());
  }

  @Bean
  @Nullable InboxCompletion axizJdbcInboxCompletion(
      InboxStore store, ObjectProvider<Clock> clocks) {
    if (!(store instanceof JdbcInboxStore jdbc)
        || !(jdbc.connectionProvider() instanceof SpringConnectionProvider provider)) return null;
    Clock clock = clocks.getIfAvailable(Clock::systemUTC);
    return new InboxCompletion() {
      @Override
      public Object transactionResource() {
        return provider.dataSource();
      }

      @Override
      public boolean complete(String consumer, String key, String owner) {
        return store.markProcessed(consumer, key, owner, clock.instant());
      }
    };
  }

  @Bean
  InboxPurger axizInboxPurger(
      InboxStore store, AxizInboxProperties p, ObjectProvider<Clock> clock) {
    return new InboxPurger(store, p.purgeInterval(), clock.getIfAvailable(Clock::systemUTC));
  }
}
