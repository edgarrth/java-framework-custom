package pe.axiz.spring.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbAsyncIdempotencyStore;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.spring.foundation.AxizAwsCompletionProperties;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.foundation.aws.AwsAsyncResources;
import pe.axiz.spring.foundation.aws.AwsCapability;
import pe.axiz.spring.foundation.aws.AwsIdentity;
import pe.axiz.spring.foundation.aws.AwsService;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB idempotency store with conditional writes and TTL. */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore")
@ConditionalOnProperty(name = "axiz.idempotency.store", havingValue = "dynamodb")
@EnableConfigurationProperties({AxizIdempotencyProperties.class, AxizAwsCompletionProperties.class})
public class AxizIdempotencyDynamoDbAutoConfiguration {

  /** Only without the DynamoDB starter (or another {@link DynamoDbClient} bean): same settings. */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  DynamoDbClient axizDynamoDbClient(
      Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
    return AwsObservability.configured(
        identity.configure(
            DynamoDbClient.builder(), AwsService.DYNAMODB, AwsCapability.IDEMPOTENCY),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "dynamodb");
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  IdempotencyStore axizDynamoDbIdempotencyStore(
      DynamoDbClient dynamo, AxizIdempotencyProperties p, ObjectProvider<Clock> clock) {
    return new DynamoDbIdempotencyStore(dynamo, p.table(), clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DynamoDbAsyncClient.class)
    AsyncAwsClients axizIdempotencyAsyncAwsResources(
        AxizAwsCompletionProperties completion,
        ObjectProvider<MeterRegistry> registries,
        AwsIdentity identity) {
      return AwsAsyncResources.create(
          "axizIdempotencyAsyncAwsResources",
          completion.workers(),
          completion.queueCapacity(),
          registries.getIfAvailable(),
          identity,
          AwsService.DYNAMODB,
          AwsCapability.IDEMPOTENCY);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DynamoDbAsyncClient axizIdempotencyDynamoDbAsyncClient(
        AsyncAwsClients axizIdempotencyAsyncAwsResources,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      return AwsObservability.configured(
          axizIdempotencyAsyncAwsResources,
          DynamoDbAsyncClient.builder(),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "dynamodb");
    }

    @Bean
    @ConditionalOnMissingBean
    AsyncIdempotencyStore axizDynamoDbAsyncIdempotencyStore(
        DynamoDbAsyncClient client, AxizIdempotencyProperties p, ObjectProvider<Clock> clock) {
      return new DynamoDbAsyncIdempotencyStore(
          client, p.table(), clock.getIfAvailable(Clock::systemUTC));
    }
  }
}
