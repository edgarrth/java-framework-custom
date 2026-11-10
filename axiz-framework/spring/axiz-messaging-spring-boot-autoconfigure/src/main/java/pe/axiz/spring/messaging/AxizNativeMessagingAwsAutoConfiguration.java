package pe.axiz.spring.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
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
import pe.axiz.adapter.messaging.sns.SnsAsyncEventPublisher;
import pe.axiz.adapter.messaging.sqs.SqsAsyncEventPublisher;
import pe.axiz.adapter.messaging.sqs.SqsEventPublisher;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsCompletionMetrics;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.spring.foundation.AxizAwsCompletionProperties;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** Native AWS producers, created only for configured destinations or supplied async clients. */
@AutoConfiguration(afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration")
@ConditionalOnClass(
    name = {"reactor.core.publisher.Mono", "pe.axiz.adapter.observability.aws.AsyncAwsClients"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@ConditionalOnMissingBean(AsyncEventPublisher.class)
@EnableConfigurationProperties({
  AxizMessagingAwsProperties.class,
  AxizAwsCompletionProperties.class
})
public class AxizNativeMessagingAwsAutoConfiguration {
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
          AxizAwsCompletionProperties completion, ObjectProvider<MeterRegistry> registries) {
        return AwsCompletionMetrics.create(
            "axizMessagingSqsAsyncResources",
            completion.workers(),
            completion.queueCapacity(),
            registries.getIfAvailable(() -> Metrics.globalRegistry));
      }

      @Bean(destroyMethod = "close")
      SqsAsyncClient axizMessagingSqsAsyncClient(
          @Qualifier("axizMessagingSqsAsyncResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations,
          AxizMessagingAwsProperties aws) {
        return clients.configured(
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

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "software.amazon.awssdk.services.sns.SnsAsyncClient",
        "pe.axiz.adapter.messaging.sns.SnsAsyncEventPublisher"
      })
  @Conditional(SnsNeeded.class)
  static class SnsConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(SnsAsyncClient.class)
    static class ClientConfiguration {
      @Bean(destroyMethod = "close")
      AsyncAwsClients axizMessagingSnsAsyncResources(
          AxizAwsCompletionProperties completion, ObjectProvider<MeterRegistry> registries) {
        return AwsCompletionMetrics.create(
            "axizMessagingSnsAsyncResources",
            completion.workers(),
            completion.queueCapacity(),
            registries.getIfAvailable(() -> Metrics.globalRegistry));
      }

      @Bean(destroyMethod = "close")
      SnsAsyncClient axizMessagingSnsAsyncClient(
          @Qualifier("axizMessagingSnsAsyncResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations,
          AxizMessagingAwsProperties aws) {
        return clients.configured(
            SnsAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "sns",
            aws.apiCallTimeout());
      }
    }

    @Bean
    AsyncEventPublisherAdapter axizSnsAsyncPublisherAdapter(SnsAsyncClient client) {
      return new AsyncEventPublisherAdapter("sns", new SnsAsyncEventPublisher(client));
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

  static final class SnsNeeded extends AnyNestedCondition {
    SnsNeeded() {
      super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnBean(SnsAsyncClient.class)
    static class SuppliedClient {}

    @Conditional(SnsDestination.class)
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

  static final class SnsDestination extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      return new ConditionOutcome(
          destinations(context).values().stream()
              .anyMatch(value -> value.startsWith("arn:") && value.contains(":sns:")),
          "Native SNS configured destination");
    }
  }

  private static Map<String, String> destinations(ConditionContext context) {
    return Binder.get(context.getEnvironment())
        .bind("axiz.messaging.destinations", Bindable.mapOf(String.class, String.class))
        .orElse(Map.of());
  }
}
