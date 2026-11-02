package pe.axiz.spring.sequence;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB atomic counters ({@code axiz.sequence.store=dynamodb}). */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore")
@ConditionalOnProperty(name = "axiz.sequence.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizSequenceProperties.class)
public class AxizSequenceDynamoDbAutoConfiguration {

  /** Only without the DynamoDB starter (or another {@link DynamoDbClient} bean): same settings. */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  DynamoDbClient axizDynamoDbClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.configured(
        DynamoDbClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "dynamodb");
  }

  @Bean
  @ConditionalOnMissingBean
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  SequenceStore axizDynamoDbSequenceStore(DynamoDbClient dynamo, AxizSequenceProperties p) {
    return new DynamoDbSequenceStore(dynamo, p.table());
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
              .REACTIVE)
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.class)
    AsyncAwsClients axizSequenceAsyncAwsResources() {
      return new AsyncAwsClients();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient axizSequenceDynamoDbAsyncClient(
        @Qualifier("axizSequenceAsyncAwsResources") AsyncAwsClients clients,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      return clients.configured(
          software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.builder(),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "dynamodb");
    }

    @Bean
    @ConditionalOnMissingBean
    pe.axiz.core.sequence.AsyncSequenceStore axizDynamoDbAsyncSequenceStore(
        software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient client,
        AxizSequenceProperties p) {
      return new pe.axiz.adapter.sequence.dynamodb.DynamoDbAsyncSequenceStore(client, p.table());
    }
  }
}
