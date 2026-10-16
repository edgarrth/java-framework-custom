package pe.axiz.spring.lock;

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
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.dynamodb.DynamoDbDistributedLock;
import pe.axiz.core.observability.awssdk.AwsClients;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB locks (conditional writes with lease expiry). */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.core.lock.dynamodb.DynamoDbDistributedLock")
@ConditionalOnProperty(name = "axiz.lock.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizLockProperties.class)
public class AxizLockDynamoDbAutoConfiguration {

  /** Only without the DynamoDB starter (or another {@link DynamoDbClient} bean): same settings. */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  DynamoDbClient axizDynamoDbClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.configured(
        DynamoDbClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "dynamodb");
  }

  @Bean
  DistributedLock axizDynamoDbLock(
      DynamoDbClient dynamo, AxizLockProperties p, ObjectProvider<Clock> clock) {
    return new DynamoDbDistributedLock(dynamo, p.table(), clock.getIfAvailable(Clock::systemUTC));
  }
}
