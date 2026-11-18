package pe.axiz.spring.tokenization.vault.dynamodb;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbAsyncTokenVault;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbTokenVault;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.KeyringAsyncDataKeyProvider;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Operational identity/client separated from ordinary repositories and privileged maintenance. */
@AutoConfiguration(
    afterName = "pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration",
    beforeName = "pe.axiz.spring.tokenization.vault.AxizTokenizationVaultAutoConfiguration")
@EnableConfigurationProperties({
  AxizTokenizationDynamoDbProperties.class,
  AxizAwsCompletionProperties.class,
  AxizAwsAsyncTransportProperties.class
})
public class AxizTokenizationDynamoDbAutoConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnImperativeApplication
  @ConditionalOnMissingBean(name = "axizTokenVaultDynamoClient")
  DynamoDbClient axizTokenVaultDynamoClient(
      AwsIdentity identity,
      Environment environment,
      ObjectProvider<ObservationRegistry> observations) {
    return AwsObservability.configured(
        identity.configure(
            DynamoDbClient.builder(), AwsService.DYNAMODB, AwsCapability.TOKENIZATION_VAULT),
        observations.getIfAvailable(() -> ObservationRegistry.NOOP),
        environment::getProperty,
        "dynamodb");
  }

  @Bean
  @ConditionalOnImperativeApplication
  DynamoDbTokenVault axizDynamoTokenVault(
      @Qualifier("axizTokenVaultDynamoClient") DynamoDbClient client,
      AxizTokenizationDynamoDbProperties properties) {
    return new DynamoDbTokenVault(client, properties.table(), properties.requestTimeout());
  }

  @Bean
  @ConditionalOnImperativeApplication
  SmartInitializingSingleton axizDynamoVaultReadiness(
      @Qualifier("axizTokenVaultDynamoClient") DynamoDbClient client,
      AxizTokenizationDynamoDbProperties storage,
      AxizTokenizationVaultProperties vault,
      BlindIndex indexes,
      DataKeyProvider keys) {
    if (!(keys instanceof KeyringDataKeyProvider ring))
      throw new IllegalStateException("The token vault requires explicit envelope key routing");
    var readiness =
        DynamoVaultStartup.readiness(storage, vault, indexes, ring.writeKeyId(), ring.readKeyIds());
    return () -> readiness.verify(client);
  }

  /** Native SDK client with owned bounded completion resources, never a blocking wrapper. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class Native {
    @Bean
    SmartInitializingSingleton axizDynamoAsyncVaultReadiness(
        @Qualifier("axizTokenVaultDynamoAsyncClient") DynamoDbAsyncClient client,
        AxizTokenizationDynamoDbProperties storage,
        AxizTokenizationVaultProperties vault,
        BlindIndex indexes,
        AsyncDataKeyProvider keys) {
      if (!(keys instanceof KeyringAsyncDataKeyProvider ring))
        throw new IllegalStateException("The token vault requires explicit envelope key routing");
      var readiness =
          DynamoVaultStartup.readiness(
              storage, vault, indexes, ring.writeKeyId(), ring.readKeyIds());
      return () -> DynamoVaultStartup.await(readiness.verify(client), storage);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "axizTokenVaultDynamoAsyncClient")
    AsyncAwsClients axizTokenVaultAwsResources(
        AxizAwsCompletionProperties completion,
        AxizAwsAsyncTransportProperties transport,
        ObjectProvider<MeterRegistry> metrics,
        AwsIdentity identity) {
      return AwsAsyncResources.create(
          "axizTokenVaultAwsResources",
          completion.workers(),
          completion.queueCapacity(),
          metrics.getIfAvailable(),
          identity,
          AwsService.DYNAMODB,
          AwsCapability.TOKENIZATION_VAULT,
          transport.toSettings());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "axizTokenVaultDynamoAsyncClient")
    DynamoDbAsyncClient axizTokenVaultDynamoAsyncClient(
        @Qualifier("axizTokenVaultAwsResources") AsyncAwsClients resources,
        Environment environment,
        ObjectProvider<ObservationRegistry> observations) {
      return AwsObservability.configured(
          resources,
          DynamoDbAsyncClient.builder(),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          environment::getProperty,
          "dynamodb");
    }

    @Bean
    DynamoDbAsyncTokenVault axizDynamoAsyncTokenVault(
        @Qualifier("axizTokenVaultDynamoAsyncClient") DynamoDbAsyncClient client,
        AxizTokenizationDynamoDbProperties properties) {
      return new DynamoDbAsyncTokenVault(client, properties.table(), properties.requestTimeout());
    }
  }
}
