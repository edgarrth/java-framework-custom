package pe.axiz.spring.secrets;

import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.observability.awssdk.AwsClients;
import pe.axiz.core.secrets.aws.ParameterStoreResolver;
import pe.axiz.core.secrets.aws.SecretsManagerResolver;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS Secrets Manager and Parameter Store sources, each enabled explicitly. Without a client bean,
 * the client takes {@code axiz.aws.region} and {@code axiz.aws.secretsmanager.endpoint} / {@code
 * axiz.aws.ssm.endpoint} or {@code axiz.aws.endpoint}.
 */
@AutoConfiguration
@ConditionalOnClass(name = "pe.axiz.core.secrets.aws.SecretsManagerResolver")
@EnableConfigurationProperties(AxizSecretsProperties.class)
public class AxizSecretsAwsAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.secrets-manager.enabled")
  static class SecretsManagerConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SecretsManagerClient axizSecretsManagerClient(
        Environment env, ObjectProvider<ObservationRegistry> registry) {
      return AwsClients.configured(
          SecretsManagerClient.builder(),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "secretsmanager");
    }

    @Bean
    SecretSource axizSecretsManagerSource(SecretsManagerClient client, AxizSecretsProperties p) {
      return new SecretSource(
          "secrets-manager", new SecretsManagerResolver(client, p.secretsManager().prefix()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "software.amazon.awssdk.services.ssm.SsmClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.parameter-store.enabled")
  static class ParameterStoreConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SsmClient axizSsmClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
      return AwsClients.configured(
          SsmClient.builder(),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "ssm");
    }

    @Bean
    SecretSource axizParameterStoreSource(SsmClient client, AxizSecretsProperties p) {
      return new SecretSource(
          "parameter-store",
          new ParameterStoreResolver(
              client,
              Objects.requireNonNull(
                  p.parameterStore().path(), "axiz.secrets.parameter-store.path is required")));
    }
  }
}
