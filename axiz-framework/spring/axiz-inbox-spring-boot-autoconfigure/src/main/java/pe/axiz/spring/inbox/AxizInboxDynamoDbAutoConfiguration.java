package pe.axiz.spring.inbox;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbAsyncInboxStore;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.spring.foundation.AxizAwsCompletionProperties;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB inbox ({@code axiz.inbox.store=dynamodb}); expired items are removed by the table TTL.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore")
@ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "dynamodb")
@ConditionalOnBooleanProperty(name = "axiz.inbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizInboxProperties.class, AxizAwsCompletionProperties.class})
public class AxizInboxDynamoDbAutoConfiguration {

  /** Only without the DynamoDB starter (or another {@link DynamoDbClient} bean): same settings. */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  DynamoDbClient axizDynamoDbClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.configured(
        DynamoDbClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "dynamodb");
  }

  @Bean
  @ConditionalOnMissingBean
  InboxStore axizDynamoDbInboxStore(DynamoDbClient dynamo, AxizInboxProperties p) {
    return new DynamoDbInboxStore(dynamo, p.table());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(AsyncInboxStore.class)
    static class NativeStoreConfiguration {
      @Bean(destroyMethod = "close")
      @ConditionalOnMissingBean(DynamoDbAsyncClient.class)
      AsyncAwsClients axizInboxAsyncAwsResources(AxizAwsCompletionProperties completion) {
        return new AsyncAwsClients(
            "axizInboxAsyncAwsResources", completion.workers(), completion.queueCapacity());
      }

      @Bean(destroyMethod = "close")
      @ConditionalOnMissingBean
      DynamoDbAsyncClient axizInboxDynamoDbAsyncClient(
          @Qualifier("axizInboxAsyncAwsResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations) {
        return clients.configured(
            DynamoDbAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "dynamodb");
      }

      @Bean
      AsyncInboxStore axizDynamoDbAsyncInboxStore(
          DynamoDbAsyncClient client, AxizInboxProperties p) {
        return new DynamoDbAsyncInboxStore(client, p.table());
      }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AsyncInboxStore.class)
    ReactiveInbox axizDynamoDbReactiveInbox(AsyncInboxStore store) {
      return new AsyncInboxFacade(store);
    }
  }
}
