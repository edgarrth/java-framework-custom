package pe.axiz.spring.scheduling.dynamodb;

import io.micrometer.observation.ObservationRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.scheduling.AxizSchedulingProperties;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * ShedLock sobre DynamoDB ({@code axiz.scheduling.store=dynamodb}). El cliente es el {@link
 * DynamoDbClient} de la aplicación (starter de DynamoDB); si no existe y está presente {@code
 * axiz-adapter-observability-aws}, se construye con la misma configuración.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider")
@ConditionalOnProperty(name = "axiz.scheduling.store", havingValue = "dynamodb")
@ConditionalOnBooleanProperty(name = "axiz.scheduling.enabled", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizSchedulingDynamoDbProperties.class,
  AxizSchedulingProperties.class
})
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
  LockProvider axizDynamoDbLockProvider(DynamoDbClient dynamo, AxizSchedulingDynamoDbProperties p) {
    return new DynamoDBLockProvider(dynamo, p.table());
  }
}
