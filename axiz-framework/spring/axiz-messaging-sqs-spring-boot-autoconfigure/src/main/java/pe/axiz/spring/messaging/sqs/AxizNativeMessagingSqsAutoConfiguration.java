package pe.axiz.spring.messaging.sqs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.messaging.sqs.SqsAsyncEventPublisher;
import pe.axiz.adapter.messaging.sqs.SqsEventPublisher;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.messaging.AsyncEventPublisherAdapter;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Productores AWS nativos, creados solo para los destinos configurados o los clientes asíncronos
 * entregados.
 */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.messaging.AxizNativeMessagingAutoConfiguration",
    afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration")
@ConditionalOnClass(
    name = {"reactor.core.publisher.Mono", "pe.axiz.adapter.aws.transport.AsyncAwsClients"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@ConditionalOnMissingBean(AsyncEventPublisher.class)
@EnableConfigurationProperties({
  AxizMessagingSqsClientProperties.class,
  AxizAwsCompletionProperties.class
})
public class AxizNativeMessagingSqsAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "software.amazon.awssdk.services.sqs.SqsAsyncClient",
        "pe.axiz.adapter.messaging.sqs.SqsAsyncEventPublisher"
      })
  @Conditional(SqsNeeded.class)
  static class SqsConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(SqsAsyncClient.class)
    static class ClientConfiguration {
      @Bean(destroyMethod = "close")
      AsyncAwsClients axizMessagingSqsAsyncResources(
          AxizAwsCompletionProperties completion,
          ObjectProvider<MeterRegistry> registries,
          AwsIdentity identity,
          AxizAwsAsyncTransportProperties transport) {
        return AwsAsyncResources.create(
            "axizMessagingSqsAsyncResources",
            completion.workers(),
            completion.queueCapacity(),
            registries.getIfAvailable(),
            identity,
            AwsService.SQS,
            AwsCapability.MESSAGING,
            transport.toSettings());
      }

      @Bean(destroyMethod = "close")
      SqsAsyncClient axizMessagingSqsAsyncClient(
          @Qualifier("axizMessagingSqsAsyncResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations,
          AxizMessagingSqsClientProperties aws) {
        return AwsObservability.configured(
            clients,
            SqsAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "sqs",
            aws.apiCallTimeout());
      }
    }

    @Bean
    AsyncEventPublisherAdapter axizSqsAsyncPublisherAdapter(SqsAsyncClient client) {
      return new AsyncEventPublisherAdapter("sqs", new SqsAsyncEventPublisher(client));
    }
  }

  static final class SqsNeeded extends AnyNestedCondition {
    SqsNeeded() {
      super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnBean(SqsAsyncClient.class)
    static class SuppliedClient {}

    @Conditional(SqsDestination.class)
    static class ConfiguredDestination {}
  }

  static final class SqsDestination extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      return new ConditionOutcome(
          destinations(context).values().stream().anyMatch(SqsEventPublisher::isDestination),
          "Native SQS configured destination");
    }
  }

  private static Map<String, String> destinations(ConditionContext context) {
    return Binder.get(context.getEnvironment())
        .bind("axiz.messaging.destinations", Bindable.mapOf(String.class, String.class))
        .orElse(Map.of());
  }
}
