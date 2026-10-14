package pe.axiz.spring.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** ShedLock on DynamoDB ({@code axiz.scheduling.store=dynamodb}). */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider")
@ConditionalOnProperty(name = "axiz.scheduling.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizSchedulingProperties.class)
public class AxizSchedulingDynamoDbAutoConfiguration {

  @Bean
  LockProvider axizDynamoDbLockProvider(
      ObjectProvider<DynamoDbClient> dynamo, AxizSchedulingProperties p) {
    return new DynamoDBLockProvider(dynamo.getIfAvailable(DynamoDbClient::create), p.table());
  }
}
