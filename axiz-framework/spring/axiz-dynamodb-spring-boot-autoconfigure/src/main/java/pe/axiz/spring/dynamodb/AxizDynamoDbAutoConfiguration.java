package pe.axiz.spring.dynamodb;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * One DynamoDB client per application (observed, default credentials chain, optional region and
 * LocalStack endpoint from {@code axiz.aws.*}), the enhanced client and the repository factory.
 */
@AutoConfiguration(
    afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration",
    beforeName = "io.awspring.cloud.autoconfigure.dynamodb.DynamoDbAutoConfiguration")
@ConditionalOnClass(name = "software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient")
public class AxizDynamoDbAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  DynamoDbClient axizDynamoDbClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.build(
        DynamoDbClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env.getProperty("axiz.aws.region"),
        env.getProperty("axiz.aws.dynamodb.endpoint", env.getProperty("axiz.aws.endpoint", "")));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  DynamoDbEnhancedClient axizDynamoDbEnhancedClient(DynamoDbClient client) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  DynamoRepositories axizDynamoRepositories(
      DynamoDbEnhancedClient client, Environment env, ObjectProvider<CursorCodec> cursors) {
    return new DynamoRepositories(
        client, env.getProperty("axiz.dynamodb.table-prefix", ""), cursors::getObject);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnMissingBean(ReactiveDynamoRepositories.class)
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DynamoDbAsyncClient.class)
    AsyncAwsClients axizRepositoryAsyncAwsResources() {
      return new AsyncAwsClients();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DynamoDbAsyncClient axizRepositoryDynamoDbAsyncClient(
        @Qualifier("axizRepositoryAsyncAwsResources") AsyncAwsClients clients,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      return clients.configured(
          DynamoDbAsyncClient.builder(),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "dynamodb");
    }

    @Bean
    ReactiveDynamoRepositories axizReactiveDynamoRepositories(
        DynamoDbAsyncClient client, Environment env, ObjectProvider<CursorCodec> cursors) {
      return new ReactiveDynamoRepositories(
          client, env.getProperty("axiz.dynamodb.table-prefix", ""), cursors::getObject);
    }
  }
}
