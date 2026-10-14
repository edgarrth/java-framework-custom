package pe.axiz.spring.dynamodb;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.observability.awssdk.AwsClients;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * One DynamoDB client per application (observed, default credentials chain, optional region and
 * LocalStack endpoint from {@code axiz.aws.*}), the enhanced client and the repository factory.
 */
@AutoConfiguration(
    beforeName = "io.awspring.cloud.autoconfigure.dynamodb.DynamoDbAutoConfiguration")
@ConditionalOnClass(name = "software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient")
public class AxizDynamoDbAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  DynamoDbClient axizDynamoDbClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.build(
        DynamoDbClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env.getProperty("axiz.aws.region"),
        env.getProperty("axiz.aws.dynamodb.endpoint", env.getProperty("axiz.aws.endpoint", "")));
  }

  @Bean
  @ConditionalOnMissingBean
  DynamoDbEnhancedClient axizDynamoDbEnhancedClient(DynamoDbClient client) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
  }

  @Bean
  @ConditionalOnMissingBean
  DynamoRepositories axizDynamoRepositories(DynamoDbEnhancedClient client, Environment env) {
    return new DynamoRepositories(client, env.getProperty("axiz.dynamodb.table-prefix", ""));
  }
}
