package pe.axiz.spring.idempotency;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.dynamodb.DynamoDbIdempotencyStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB idempotency store with conditional writes and TTL. */
@AutoConfiguration
@ConditionalOnClass(name = "pe.axiz.core.idempotency.dynamodb.DynamoDbIdempotencyStore")
@ConditionalOnProperty(name = "axiz.idempotency.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizIdempotencyProperties.class)
public class AxizIdempotencyDynamoDbAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  IdempotencyStore axizDynamoDbIdempotencyStore(
      ObjectProvider<DynamoDbClient> dynamo,
      AxizIdempotencyProperties p,
      ObjectProvider<Clock> clock) {
    return new DynamoDbIdempotencyStore(
        dynamo.getIfAvailable(DynamoDbClient::create),
        p.table(),
        clock.getIfAvailable(Clock::systemUTC));
  }
}
