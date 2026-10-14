package pe.axiz.spring.inbox;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.dynamodb.DynamoDbInboxStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB inbox ({@code axiz.inbox.store=dynamodb}); expired items are removed by the table TTL.
 */
@AutoConfiguration
@ConditionalOnClass(name = "pe.axiz.core.inbox.dynamodb.DynamoDbInboxStore")
@ConditionalOnProperty(name = "axiz.inbox.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizInboxProperties.class)
public class AxizInboxDynamoDbAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  InboxStore axizDynamoDbInboxStore(ObjectProvider<DynamoDbClient> dynamo, AxizInboxProperties p) {
    return new DynamoDbInboxStore(dynamo.getIfAvailable(DynamoDbClient::create), p.table());
  }
}
