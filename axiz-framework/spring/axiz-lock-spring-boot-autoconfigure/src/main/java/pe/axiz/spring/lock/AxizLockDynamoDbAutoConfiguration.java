package pe.axiz.spring.lock;

import pe.axiz.spring.foundation.AxizAwsCompletionProperties;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.lock.dynamodb.DynamoDbDistributedLock;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.core.lock.DistributedLock;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB locks (conditional writes with lease expiry). */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.lock.dynamodb.DynamoDbDistributedLock")
@ConditionalOnProperty(name = "axiz.lock.store", havingValue = "dynamodb")
@EnableConfigurationProperties({AxizLockProperties.class, AxizAwsCompletionProperties.class})
public class AxizLockDynamoDbAutoConfiguration {

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
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  DistributedLock axizDynamoDbLock(
      DynamoDbClient dynamo, AxizLockProperties p, ObjectProvider<Clock> clock) {
    return new DynamoDbDistributedLock(dynamo, p.table(), clock.getIfAvailable(Clock::systemUTC));
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
              .REACTIVE)
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.class)
    AsyncAwsClients axizLockAsyncAwsResources(AxizAwsCompletionProperties completion) {
      return new AsyncAwsClients("axizLockAsyncAwsResources", completion.workers(), completion.queueCapacity());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient axizLockDynamoDbAsyncClient(
        @Qualifier("axizLockAsyncAwsResources") AsyncAwsClients clients,
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
    pe.axiz.core.lock.AsyncDistributedLock axizDynamoDbAsyncLock(
        software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient client,
        AxizLockProperties p,
        ObjectProvider<Clock> clock) {
      return new pe.axiz.adapter.lock.dynamodb.DynamoDbAsyncDistributedLock(
          client, p.table(), clock.getIfAvailable(Clock::systemUTC));
    }
  }
}
