package pe.axiz.spring.sequence.dynamodb;

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
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbAsyncSequenceStore;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.sequence.AxizSequenceProperties;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Contadores atómicos de DynamoDB ({@code axiz.sequence.store=dynamodb}). */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore")
@ConditionalOnProperty(name = "axiz.sequence.store", havingValue = "dynamodb")
@ConditionalOnBooleanProperty(name = "axiz.sequence.enabled", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizSequenceDynamoDbProperties.class,
  AxizSequenceProperties.class,
  AxizAwsCompletionProperties.class
})
public class AxizSequenceDynamoDbAutoConfiguration {

  /** Solo sin el starter de DynamoDB (u otro bean {@link DynamoDbClient}): misma configuración. */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  DynamoDbClient axizDynamoDbClient(
      Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
    return AwsObservability.configured(
        identity.configure(DynamoDbClient.builder(), AwsService.DYNAMODB, AwsCapability.SEQUENCE),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "dynamodb");
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  SequenceStore axizDynamoDbSequenceStore(DynamoDbClient dynamo, AxizSequenceDynamoDbProperties p) {
    return new DynamoDbSequenceStore(dynamo, p.table());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DynamoDbAsyncClient.class)
    AsyncAwsClients axizSequenceAsyncAwsResources(
        AxizAwsCompletionProperties completion,
        ObjectProvider<MeterRegistry> registries,
        AwsIdentity identity,
        AxizAwsAsyncTransportProperties transport) {
      return AwsAsyncResources.create(
          "axizSequenceAsyncAwsResources",
          completion.workers(),
          completion.queueCapacity(),
          registries.getIfAvailable(),
          identity,
          AwsService.DYNAMODB,
          AwsCapability.SEQUENCE,
          transport.toSettings());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DynamoDbAsyncClient axizSequenceDynamoDbAsyncClient(
        @Qualifier("axizSequenceAsyncAwsResources") AsyncAwsClients clients,
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
    @ConditionalOnMissingBean
    AsyncSequenceStore axizDynamoDbAsyncSequenceStore(
        DynamoDbAsyncClient client, AxizSequenceDynamoDbProperties p) {
      return new DynamoDbAsyncSequenceStore(client, p.table());
    }
  }
}
