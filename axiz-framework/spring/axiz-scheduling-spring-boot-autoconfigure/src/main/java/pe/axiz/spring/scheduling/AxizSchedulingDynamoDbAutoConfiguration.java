package pe.axiz.spring.scheduling;

import io.micrometer.observation.ObservationRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.spring.foundation.aws.AwsCapability;
import pe.axiz.spring.foundation.aws.AwsIdentity;
import pe.axiz.spring.foundation.aws.AwsService;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * ShedLock on DynamoDB ({@code axiz.scheduling.store=dynamodb}). The client is the application's
 * {@link DynamoDbClient} (DynamoDB starter); without one, and with {@code
 * axiz-adapter-observability-aws} present, it is built with the same settings.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider")
@ConditionalOnProperty(name = "axiz.scheduling.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizSchedulingProperties.class)
public class AxizSchedulingDynamoDbAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.adapter.observability.aws.AwsObservability")
  static class Client {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DynamoDbClient axizDynamoDbClient(
        Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
      return AwsObservability.configured(
          identity.configure(
              DynamoDbClient.builder(), AwsService.DYNAMODB, AwsCapability.SCHEDULING),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "dynamodb");
    }
  }

  @Bean
  LockProvider axizDynamoDbLockProvider(DynamoDbClient dynamo, AxizSchedulingProperties p) {
    return new DynamoDBLockProvider(dynamo, p.table());
  }
}
