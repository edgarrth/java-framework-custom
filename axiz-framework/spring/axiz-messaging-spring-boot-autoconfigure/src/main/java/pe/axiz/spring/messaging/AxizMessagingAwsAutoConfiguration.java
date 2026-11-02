package pe.axiz.spring.messaging;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.messaging.sns.SnsEventPublisher;
import pe.axiz.adapter.messaging.sqs.SqsEventPublisher;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.core.resilience.Backoff;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Amazon SQS and SNS publishers (FIFO deduplication id = event id) and SQS consumers. Credentials
 * come from the SDK default chain (IRSA or EKS Pod Identity).
 */
@AutoConfiguration
@org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty(
    name = "axiz.messaging.enabled",
    matchIfMissing = true)
@EnableConfigurationProperties(AxizMessagingProperties.class)
public class AxizMessagingAwsAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "pe.axiz.adapter.messaging.sqs.SqsEventPublisher",
        "software.amazon.awssdk.services.sqs.SqsClient",
        "pe.axiz.adapter.observability.aws.AwsClients"
      })
  static class SqsConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SqsClient axizSqsClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
      return AwsClients.build(
          SqsClient.builder(),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env.getProperty("axiz.aws.region"),
          env.getProperty("axiz.aws.sqs.endpoint", env.getProperty("axiz.aws.endpoint", "")));
    }

    @Bean
    EventPublisherAdapter axizSqsPublisherAdapter(SqsClient sqs) {
      return new EventPublisherAdapter("sqs", new SqsEventPublisher(sqs));
    }

    @Bean
    @ConditionalOnListProperty("axiz.messaging.sqs.queues")
    SqsEventConsumer axizSqsEventConsumer(
        SqsClient sqs, ObservedDispatcher dispatcher, AxizMessagingProperties p) {
      AxizMessagingProperties.Consumer c = p.consumer();
      return new SqsEventConsumer(
          sqs, dispatcher, p.sqs(), Backoff.exponential(c.initialDelay(), c.maxDelay()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "pe.axiz.adapter.messaging.sns.SnsEventPublisher",
        "software.amazon.awssdk.services.sns.SnsClient",
        "pe.axiz.adapter.observability.aws.AwsClients"
      })
  static class SnsConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SnsClient axizSnsClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
      return AwsClients.build(
          SnsClient.builder(),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env.getProperty("axiz.aws.region"),
          env.getProperty("axiz.aws.sns.endpoint", env.getProperty("axiz.aws.endpoint", "")));
    }

    @Bean
    EventPublisherAdapter axizSnsPublisherAdapter(SnsClient sns) {
      return new EventPublisherAdapter("sns", new SnsEventPublisher(sns));
    }
  }
}
