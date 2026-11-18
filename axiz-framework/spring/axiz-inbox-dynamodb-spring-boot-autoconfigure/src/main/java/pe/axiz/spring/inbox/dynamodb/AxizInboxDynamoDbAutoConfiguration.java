package pe.axiz.spring.inbox.dynamodb;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbAsyncInboxStore;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.inbox.AxizInboxProperties;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB inbox ({@code axiz.inbox.store=dynamodb}); expired items are removed by the table TTL.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore")
@ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "dynamodb")
@ConditionalOnBooleanProperty(name = "axiz.inbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizInboxDynamoDbProperties.class,
  AxizInboxProperties.class,
  AxizAwsCompletionProperties.class
})
public class AxizInboxDynamoDbAutoConfiguration {

  /** Only without the DynamoDB starter (or another {@link DynamoDbClient} bean): same settings. */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  DynamoDbClient axizDynamoDbClient(
      Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
    return AwsObservability.configured(
        identity.configure(DynamoDbClient.builder(), AwsService.DYNAMODB, AwsCapability.INBOX),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "dynamodb");
  }

  @Bean
  @ConditionalOnMissingBean
  InboxStore axizDynamoDbInboxStore(DynamoDbClient dynamo, AxizInboxDynamoDbProperties p) {
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
      AsyncAwsClients axizInboxAsyncAwsResources(
          AxizAwsCompletionProperties completion,
          ObjectProvider<MeterRegistry> registries,
          AwsIdentity identity,
          AxizAwsAsyncTransportProperties transport) {
        return AwsAsyncResources.create(
            "axizInboxAsyncAwsResources",
            completion.workers(),
            completion.queueCapacity(),
            registries.getIfAvailable(),
            identity,
            AwsService.DYNAMODB,
            AwsCapability.INBOX,
            transport.toSettings());
      }

      @Bean(destroyMethod = "close")
      @ConditionalOnMissingBean
      DynamoDbAsyncClient axizInboxDynamoDbAsyncClient(
          @Qualifier("axizInboxAsyncAwsResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations) {
        return AwsObservability.configured(
            clients,
            DynamoDbAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "dynamodb");
      }

      @Bean
      AsyncInboxStore axizDynamoDbAsyncInboxStore(
          DynamoDbAsyncClient client, AxizInboxDynamoDbProperties p) {
        return new DynamoDbAsyncInboxStore(client, p.table());
      }
    }
  }
}
