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
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.core.observability.awssdk.AwsClients;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB idempotency store with conditional writes and TTL. */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.core.idempotency.dynamodb.DynamoDbIdempotencyStore")
@ConditionalOnProperty(name = "axiz.idempotency.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizIdempotencyProperties.class)
public class AxizIdempotencyDynamoDbAutoConfiguration {

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
  @ConditionalOnMissingBean
  IdempotencyStore axizDynamoDbIdempotencyStore(
      DynamoDbClient dynamo, AxizIdempotencyProperties p, ObjectProvider<Clock> clock) {
    return new DynamoDbIdempotencyStore(dynamo, p.table(), clock.getIfAvailable(Clock::systemUTC));
  }
}
