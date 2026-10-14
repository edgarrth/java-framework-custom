package pe.axiz.spring.sequence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.core.sequence.dynamodb.DynamoDbSequenceStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** DynamoDB atomic counters ({@code axiz.sequence.store=dynamodb}). */
@AutoConfiguration(afterName = "pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.core.sequence.dynamodb.DynamoDbSequenceStore")
@ConditionalOnProperty(name = "axiz.sequence.store", havingValue = "dynamodb")
@EnableConfigurationProperties(AxizSequenceProperties.class)
public class AxizSequenceDynamoDbAutoConfiguration {

  @Bean
  SequenceStore axizDynamoDbSequenceStore(
      ObjectProvider<DynamoDbClient> dynamo, AxizSequenceProperties p) {
    return new DynamoDbSequenceStore(dynamo.getIfAvailable(DynamoDbClient::create), p.table());
  }
}
