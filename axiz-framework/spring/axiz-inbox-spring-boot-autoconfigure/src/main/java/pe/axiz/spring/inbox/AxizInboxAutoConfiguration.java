package pe.axiz.spring.inbox;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxDeduplicator;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.messaging.Deduplicator;

/**
 * Inbox: the messaging dispatcher skips event ids a consumer already processed. Storage is Aurora
 * (JDBC by default, R2DBC for reactive services) or DynamoDB with TTL.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.inbox.jdbc.AxizInboxJdbcAutoConfiguration",
      "pe.axiz.spring.inbox.dynamodb.AxizInboxDynamoDbAutoConfiguration",
      "pe.axiz.spring.inbox.r2dbc.AxizInboxR2dbcAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.inbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizInboxProperties.class)
public class AxizInboxAutoConfiguration {

  /**
   * Fails startup (before consumers start) when the inbox table lacks a column this version needs,
   * instead of failing every message later.
   */
  @Bean
  SmartInitializingSingleton axizInboxSchemaCheck(ObjectProvider<InboxStore> store) {
    return () -> {
      InboxStore selected = store.getIfAvailable();
      if (selected == null)
        throw new IllegalStateException(
            "Inbox requires its selected storage integration or an InboxStore bean");
      selected.verifySchema();
    };
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(InboxStore.class)
  Deduplicator axizInboxDeduplicator(
      InboxStore store, AxizInboxProperties p, ObjectProvider<Clock> clock) {
    return new InboxDeduplicator(
        store, p.lease(), p.retention(), clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class NativeConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AsyncInboxStore.class)
    ReactiveInbox axizAsyncInbox(AsyncInboxStore store) {
      return new AsyncInboxFacade(store);
    }
  }
}
