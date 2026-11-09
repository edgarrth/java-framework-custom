package pe.axiz.spring.secrets;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.adapter.secrets.aws.ParameterStoreResolver;
import pe.axiz.adapter.secrets.aws.SecretsManagerResolver;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.spring.foundation.AxizAwsCompletionProperties;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS Secrets Manager and Parameter Store sources, each enabled explicitly. Without a client bean,
 * the client takes {@code axiz.aws.region} and {@code axiz.aws.secretsmanager.endpoint} / {@code
 * axiz.aws.ssm.endpoint} or {@code axiz.aws.endpoint}.
 */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.secrets.AxizSecretsNativeAwsAutoConfiguration",
    afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.secrets.aws.SecretsManagerResolver")
@ConditionalOnBooleanProperty(name = "axiz.secrets.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizSecretsProperties.class, AxizAwsCompletionProperties.class})
public class AxizSecretsAwsAutoConfiguration {

  /**
   * Register the shared owner before native default client definitions. Their own definitions must
   * not be mistaken for user-supplied clients when deciding whether an owner is needed.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnMissingBean(AsyncSecretResolver.class)
  @Conditional(AxizSecretsNativeAwsAutoConfiguration.OwnerNeeded.class)
  AsyncAwsClients axizSecretsAsyncAwsResources(AxizAwsCompletionProperties completion) {
    return new AsyncAwsClients(
        "axizSecretsAsyncAwsResources", completion.workers(), completion.queueCapacity());
  }

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
      String path = p.parameterStore().path();
      if (path == null || path.isBlank()) {
        throw new IllegalStateException(
            "axiz.secrets.parameter-store.path is required when the Parameter Store source is"
                + " enabled");
      }
      return new SecretSource("parameter-store", new ParameterStoreResolver(client, path));
    }
  }
}
