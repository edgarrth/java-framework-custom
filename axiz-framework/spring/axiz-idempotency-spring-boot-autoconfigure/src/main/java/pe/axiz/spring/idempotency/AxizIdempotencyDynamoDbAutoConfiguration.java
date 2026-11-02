package pe.axiz.spring.idempotency;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.core.idempotency.IdempotencyStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB idempotency store with conditional writes and TTL. */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore")
@ConditionalOnProperty(name = "axiz.idempotency.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizIdempotencyProperties.class)
public class AxizIdempotencyDynamoDbAutoConfiguration {

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
  IdempotencyStore axizDynamoDbIdempotencyStore(
      DynamoDbClient dynamo, AxizIdempotencyProperties p, ObjectProvider<Clock> clock) {
    return new DynamoDbIdempotencyStore(dynamo, p.table(), clock.getIfAvailable(Clock::systemUTC));
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
              .REACTIVE)
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.class)
    pe.axiz.adapter.observability.aws.AsyncAwsClients axizIdempotencyAsyncAwsResources() {
      return new pe.axiz.adapter.observability.aws.AsyncAwsClients();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient axizIdempotencyDynamoDbAsyncClient(
        pe.axiz.adapter.observability.aws.AsyncAwsClients axizIdempotencyAsyncAwsResources,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      return axizIdempotencyAsyncAwsResources.configured(
          software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.builder(),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "dynamodb");
    }

    @Bean
    @ConditionalOnMissingBean
    pe.axiz.core.idempotency.AsyncIdempotencyStore axizDynamoDbAsyncIdempotencyStore(
        software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient client,
        AxizIdempotencyProperties p,
        ObjectProvider<Clock> clock) {
      return new pe.axiz.adapter.idempotency.dynamodb.DynamoDbAsyncIdempotencyStore(
          client, p.table(), clock.getIfAvailable(Clock::systemUTC));
    }
  }
}
