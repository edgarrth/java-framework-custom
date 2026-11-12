package pe.axiz.spring.secrets;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.adapter.secrets.aws.ParameterStoreAsyncResolver;
import pe.axiz.adapter.secrets.aws.SecretsManagerAsyncResolver;
import pe.axiz.core.secrets.AsyncSecretResolver;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;

/** Native AWS request sources; the existing synchronous sources serve bootstrap consumers only. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration",
    afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.secrets.aws.SecretsManagerAsyncResolver",
      "reactor.core.publisher.Mono"
    })
@ConditionalOnBooleanProperty(name = "axiz.secrets.enabled", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnMissingBean(AsyncSecretResolver.class)
@EnableConfigurationProperties(AxizSecretsProperties.class)
public class AxizSecretsNativeAwsAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = "software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.secrets-manager.enabled")
  static class ManagerConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SecretsManagerAsyncClient axizSecretsManagerAsyncClient(
        @Qualifier("axizSecretsManagerAsyncAwsResources") AsyncAwsClients clients,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      return AwsObservability.configured(
          clients,
          SecretsManagerAsyncClient.builder(),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "secretsmanager");
    }

    @Bean
    @Order(100)
    AsyncSecretSource axizSecretsManagerAsyncSource(
        SecretsManagerAsyncClient client, AxizSecretsProperties p) {
      return new AsyncSecretSource(
          "secrets-manager", new SecretsManagerAsyncResolver(client, p.secretsManager().prefix()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "software.amazon.awssdk.services.ssm.SsmAsyncClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.parameter-store.enabled")
  static class ParameterConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SsmAsyncClient axizSsmAsyncClient(
        @Qualifier("axizSsmAsyncAwsResources") AsyncAwsClients clients,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      return AwsObservability.configured(
          clients,
          SsmAsyncClient.builder(),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "ssm");
    }

    @Bean
    @Order(200)
    AsyncSecretSource axizParameterAsyncSource(SsmAsyncClient client, AxizSecretsProperties p) {
      String path = p.parameterStore().path();
      if (path == null || path.isBlank())
        throw new IllegalStateException(
            "axiz.secrets.parameter-store.path is required when the Parameter Store source is"
                + " enabled");
      return new AsyncSecretSource(
          "parameter-store", new ParameterStoreAsyncResolver(client, path));
    }
  }
}
