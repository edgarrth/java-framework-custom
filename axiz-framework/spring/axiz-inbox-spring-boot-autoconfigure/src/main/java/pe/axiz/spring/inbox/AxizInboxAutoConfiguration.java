package pe.axiz.spring.inbox;

import java.time.Clock;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.core.inbox.InboxDeduplicator;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.spring.foundation.InboxCompletion;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;

/**
 * Inbox: the messaging dispatcher skips event ids a consumer already processed. Storage is Aurora
 * (JDBC by default, R2DBC for reactive services) or DynamoDB with TTL.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "pe.axiz.spring.inbox.AxizInboxDynamoDbAutoConfiguration",
      "pe.axiz.spring.inbox.AxizInboxR2dbcAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.inbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizInboxProperties.class)
public class AxizInboxAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "jdbc", matchIfMissing = true)
  static class JdbcConfiguration {

    @Bean
    SchemaInitializer axizInboxSchema(DataSource dataSource, AxizInboxProperties p) {
      return new SchemaInitializer(dataSource, "inbox", p.initializeSchema());
    }

    @Bean
    @ConditionalOnMissingBean
    InboxStore axizInboxStore(
        DataSource dataSource, AxizInboxProperties p, SchemaInitializer axizInboxSchema) {
      return new JdbcInboxStore(new SpringConnectionProvider(dataSource), p.table());
    }

    @Bean
    @Nullable InboxCompletion axizJdbcInboxCompletion(
        InboxStore store, ObjectProvider<Clock> clocks) {
      if (!(store instanceof JdbcInboxStore jdbc)
          || !(jdbc.connectionProvider() instanceof SpringConnectionProvider provider)) return null;
      Clock clock = clocks.getIfAvailable(Clock::systemUTC);
      return new InboxCompletion() {
        public Object transactionResource() {
          return provider.dataSource();
        }

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

  /**
   * Fails startup (before consumers start) when the inbox table lacks a column this version needs,
   * instead of failing every message later.
   */
  @Bean
  SmartInitializingSingleton axizInboxSchemaCheck(ObjectProvider<InboxStore> store) {
    return () -> store.ifAvailable(InboxStore::verifySchema);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(InboxStore.class)
  Deduplicator axizInboxDeduplicator(
      InboxStore store, AxizInboxProperties p, ObjectProvider<Clock> clock) {
    return new InboxDeduplicator(
        store, p.lease(), p.retention(), clock.getIfAvailable(Clock::systemUTC));
  }
}
