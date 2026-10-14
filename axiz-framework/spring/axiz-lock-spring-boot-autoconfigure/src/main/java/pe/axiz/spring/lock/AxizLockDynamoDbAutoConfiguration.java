package pe.axiz.spring.lock;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.dynamodb.DynamoDbDistributedLock;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB locks (conditional writes with lease expiry). */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.core.lock.dynamodb.DynamoDbDistributedLock")
@ConditionalOnProperty(name = "axiz.lock.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizLockProperties.class)
public class AxizLockDynamoDbAutoConfiguration {

  @Bean
  DistributedLock axizDynamoDbLock(
      ObjectProvider<DynamoDbClient> dynamo, AxizLockProperties p, ObjectProvider<Clock> clock) {
    return new DynamoDbDistributedLock(
        dynamo.getIfAvailable(DynamoDbClient::create),
        p.table(),
        clock.getIfAvailable(Clock::systemUTC));
  }
}
