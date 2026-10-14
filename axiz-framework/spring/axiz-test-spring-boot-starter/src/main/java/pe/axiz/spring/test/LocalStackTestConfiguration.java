package pe.axiz.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.localstack.LocalStackContainer;

/**
 * LocalStack for the AWS adapters. The framework clients read {@code axiz.aws.endpoint} and {@code
 * axiz.aws.region}; LocalStack's fixed test credentials are given to the SDK default chain through
 * system properties (they are not secrets).
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalStackTestConfiguration {

  @Bean(initMethod = "start", destroyMethod = "stop")
  LocalStackContainer axizLocalStackContainer() {
    return AxizContainers.localstack();
  }

  @Bean
  DynamicPropertyRegistrar axizLocalStackProperties(LocalStackContainer localstack) {
    System.setProperty("aws.accessKeyId", localstack.getAccessKey());
    System.setProperty("aws.secretAccessKey", localstack.getSecretKey());
    return registry -> {
      registry.add("axiz.aws.endpoint", () -> localstack.getEndpoint().toString());
      registry.add("axiz.aws.region", localstack::getRegion);
      // Spring Cloud AWS starters (DynamoDB, S3, Parameter Store) build their own clients
      registry.add("spring.cloud.aws.endpoint", () -> localstack.getEndpoint().toString());
      registry.add("spring.cloud.aws.region.static", localstack::getRegion);
      registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
      registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
    };
  }
}
