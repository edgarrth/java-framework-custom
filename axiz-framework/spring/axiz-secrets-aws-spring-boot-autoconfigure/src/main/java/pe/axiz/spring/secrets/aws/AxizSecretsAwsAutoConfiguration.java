package pe.axiz.spring.secrets.aws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
import pe.axiz.adapter.secrets.aws.ParameterStoreResolver;
import pe.axiz.adapter.secrets.aws.SecretsManagerResolver;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.secrets.SecretSource;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS Secrets Manager and Parameter Store sources, each enabled explicitly. Without a client bean,
 * the client takes {@code axiz.aws.region} and {@code axiz.aws.secretsmanager.endpoint} / {@code
 * axiz.aws.ssm.endpoint} or {@code axiz.aws.endpoint}.
 */
@AutoConfiguration(
    beforeName = {
      "pe.axiz.spring.secrets.aws.AxizSecretsNativeAwsAutoConfiguration",
      "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration"
    },
    afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration")
@ConditionalOnClass(name = "pe.axiz.adapter.secrets.aws.SecretsManagerResolver")
@ConditionalOnBooleanProperty(name = "axiz.secrets.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizSecretsAwsProperties.class, AxizAwsCompletionProperties.class})
public class AxizSecretsAwsAutoConfiguration {

  // Register each owner before native clients; each AWS service can use a different identity.
  @Bean(destroyMethod = "close")
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnBooleanProperty(name = "axiz.secrets.secrets-manager.enabled")
  @ConditionalOnMissingBean(
      value = AsyncSecretResolver.class,
      type = "software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient")
  AsyncAwsClients axizSecretsManagerAsyncAwsResources(
      AxizAwsCompletionProperties completion,
      ObjectProvider<MeterRegistry> registries,
      AwsIdentity identity,
      AxizAwsAsyncTransportProperties transport) {
    return AwsAsyncResources.create(
        "axizSecretsManagerAsyncAwsResources",
        completion.workers(),
        completion.queueCapacity(),
        registries.getIfAvailable(),
        identity,
        AwsService.SECRETS_MANAGER,
        AwsCapability.SECRETS,
        transport.toSettings());
  }

  // Register each owner before native clients; each AWS service can use a different identity.
  @Bean(destroyMethod = "close")
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnBooleanProperty(name = "axiz.secrets.parameter-store.enabled")
  @ConditionalOnMissingBean(
      value = AsyncSecretResolver.class,
      type = "software.amazon.awssdk.services.ssm.SsmAsyncClient")
  AsyncAwsClients axizSsmAsyncAwsResources(
      AxizAwsCompletionProperties completion,
      ObjectProvider<MeterRegistry> registries,
      AwsIdentity identity,
      AxizAwsAsyncTransportProperties transport) {
    return AwsAsyncResources.create(
        "axizSsmAsyncAwsResources",
        completion.workers(),
        completion.queueCapacity(),
        registries.getIfAvailable(),
        identity,
        AwsService.SSM,
        AwsCapability.SECRETS,
        transport.toSettings());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.secrets-manager.enabled")
  @ConditionalOnMissingBean(SecretResolver.class)
  static class SecretsManagerConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SecretsManagerClient axizSecretsManagerClient(
        Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
      return AwsObservability.configured(
          identity.configure(
              SecretsManagerClient.builder(), AwsService.SECRETS_MANAGER, AwsCapability.SECRETS),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "secretsmanager");
    }

    @Bean
    @Order(100)
    SecretSource axizSecretsManagerSource(SecretsManagerClient client, AxizSecretsAwsProperties p) {
      return new SecretSource(
          "secrets-manager", new SecretsManagerResolver(client, p.secretsManager().prefix()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "software.amazon.awssdk.services.ssm.SsmClient")
  @ConditionalOnBooleanProperty(name = "axiz.secrets.parameter-store.enabled")
  @ConditionalOnMissingBean(SecretResolver.class)
  static class ParameterStoreConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SsmClient axizSsmClient(
        Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
      return AwsObservability.configured(
          identity.configure(SsmClient.builder(), AwsService.SSM, AwsCapability.SECRETS),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "ssm");
    }

    @Bean
    @Order(200)
    SecretSource axizParameterStoreSource(SsmClient client, AxizSecretsAwsProperties p) {
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
