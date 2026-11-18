package pe.axiz.spring.lock.dynamodb;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
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
import pe.axiz.adapter.lock.dynamodb.DynamoDbAsyncDistributedLock;
import pe.axiz.adapter.lock.dynamodb.DynamoDbDistributedLock;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB locks (conditional writes with lease expiry). */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.lock.AxizLockAutoConfiguration",
    afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.lock.dynamodb.DynamoDbDistributedLock")
@ConditionalOnProperty(name = "axiz.lock.store", havingValue = "dynamodb")
@EnableConfigurationProperties({
  AxizLockDynamoDbProperties.class,
  AxizAwsCompletionProperties.class
})
@ConditionalOnBooleanProperty(name = "axiz.lock.enabled", matchIfMissing = true)
public class AxizLockDynamoDbAutoConfiguration {

  /** Only without the DynamoDB starter (or another {@link DynamoDbClient} bean): same settings. */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  DynamoDbClient axizDynamoDbClient(
      Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
    return AwsObservability.configured(
        identity.configure(DynamoDbClient.builder(), AwsService.DYNAMODB, AwsCapability.LOCK),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "dynamodb");
  }

  @Bean
  @ConditionalOnImperativeApplication
  @ConditionalOnMissingBean(DistributedLock.class)
  DistributedLock axizDynamoDbLock(
      DynamoDbClient dynamo, AxizLockDynamoDbProperties p, ObjectProvider<Clock> clock) {
    return new DynamoDbDistributedLock(dynamo, p.table(), clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DynamoDbAsyncClient.class)
    AsyncAwsClients axizLockAsyncAwsResources(
        AxizAwsCompletionProperties completion,
        ObjectProvider<MeterRegistry> registries,
        AwsIdentity identity,
        AxizAwsAsyncTransportProperties transport) {
      return AwsAsyncResources.create(
          "axizLockAsyncAwsResources",
          completion.workers(),
          completion.queueCapacity(),
          registries.getIfAvailable(),
          identity,
          AwsService.DYNAMODB,
          AwsCapability.LOCK,
          transport.toSettings());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DynamoDbAsyncClient axizLockDynamoDbAsyncClient(
        @Qualifier("axizLockAsyncAwsResources") AsyncAwsClients clients,
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
    AsyncDistributedLock axizDynamoDbAsyncLock(
        DynamoDbAsyncClient client, AxizLockDynamoDbProperties p, ObjectProvider<Clock> clock) {
      return new DynamoDbAsyncDistributedLock(
          client, p.table(), clock.getIfAvailable(Clock::systemUTC));
    }
  }
}
