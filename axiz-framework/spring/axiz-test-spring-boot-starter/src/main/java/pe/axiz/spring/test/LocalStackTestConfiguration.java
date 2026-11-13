package pe.axiz.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * LocalStack for the AWS adapters. The framework clients read {@code axiz.aws.endpoint} and {@code
 * axiz.aws.region}; LocalStack credentials are scoped to the Spring test context through a provider
 * bean.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalStackTestConfiguration {

  @Bean(initMethod = "start", destroyMethod = "stop")
  LocalStackContainer axizLocalStackContainer() {
    return AxizContainers.localstack();
  }

  @Bean
  AwsCredentialsProvider axizLocalStackCredentialsProvider(LocalStackContainer localstack) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
  }

  @Bean
  DynamicPropertyRegistrar axizLocalStackProperties(LocalStackContainer localstack) {
    return registry -> {
      registry.add("axiz.config.environment", () -> "test");
      registry.add("axiz.transport.allow-plain-http", () -> "true");
      registry.add("axiz.aws.endpoint", () -> localstack.getEndpoint().toString());
      registry.add("axiz.aws.region", localstack::getRegion);
      // The Cloud AWS Parameter Store integration borrows the explicit context-global bean.
      registry.add("spring.cloud.aws.endpoint", () -> localstack.getEndpoint().toString());
      registry.add("spring.cloud.aws.region.static", localstack::getRegion);
    };
  }
}
