package pe.axiz.spring.inbox;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.dynamodb.DynamoDbInboxStore;
import pe.axiz.core.observability.awssdk.AwsClients;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB inbox ({@code axiz.inbox.store=dynamodb}); expired items are removed by the table TTL.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.core.inbox.dynamodb.DynamoDbInboxStore")
@ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizInboxProperties.class)
public class AxizInboxDynamoDbAutoConfiguration {

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
  InboxStore axizDynamoDbInboxStore(DynamoDbClient dynamo, AxizInboxProperties p) {
    return new DynamoDbInboxStore(dynamo, p.table());
  }
}
