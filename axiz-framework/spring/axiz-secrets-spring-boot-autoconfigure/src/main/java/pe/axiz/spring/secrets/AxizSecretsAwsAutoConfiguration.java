package pe.axiz.spring.secrets;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.secrets.aws.ParameterStoreResolver;
import pe.axiz.core.secrets.aws.SecretsManagerResolver;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/** AWS Secrets Manager and Parameter Store sources, each enabled explicitly. */
@AutoConfiguration
@ConditionalOnClass(name = "pe.axiz.core.secrets.aws.SecretsManagerResolver")
@EnableConfigurationProperties(AxizSecretsProperties.class)
public class AxizSecretsAwsAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.secrets-manager.enabled")
  static class SecretsManagerConfiguration {

    @Bean
    SecretSource axizSecretsManagerSource(
        ObjectProvider<SecretsManagerClient> client, AxizSecretsProperties p) {
      return new SecretSource(
          "secrets-manager",
          new SecretsManagerResolver(
              client.getIfAvailable(SecretsManagerClient::create), p.secretsManager().prefix()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "software.amazon.awssdk.services.ssm.SsmClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.parameter-store.enabled")
  static class ParameterStoreConfiguration {

    @Bean
    SecretSource axizParameterStoreSource(
        ObjectProvider<SsmClient> client, AxizSecretsProperties p) {
      return new SecretSource(
          "parameter-store",
          new ParameterStoreResolver(
              client.getIfAvailable(SsmClient::create),
              Objects.requireNonNull(
                  p.parameterStore().path(), "axiz.secrets.parameter-store.path is required")));
    }
  }
}
