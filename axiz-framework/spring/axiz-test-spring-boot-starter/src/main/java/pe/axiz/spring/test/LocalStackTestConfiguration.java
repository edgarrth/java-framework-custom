package pe.axiz.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * LocalStack para los adaptadores de AWS. Los clientes del framework leen {@code axiz.aws.endpoint}
 * y {@code axiz.aws.region}; las credenciales de LocalStack se limitan al contexto de prueba de
 * Spring mediante un bean de proveedor.
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
      // La integración de Parameter Store de Cloud AWS toma prestado el bean global explícito del
      // contexto.
      registry.add("spring.cloud.aws.endpoint", () -> localstack.getEndpoint().toString());
      registry.add("spring.cloud.aws.region.static", localstack::getRegion);
    };
  }
}
