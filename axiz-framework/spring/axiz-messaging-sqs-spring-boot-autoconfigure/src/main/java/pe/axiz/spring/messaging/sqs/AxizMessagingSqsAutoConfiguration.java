package pe.axiz.spring.messaging.sqs;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.messaging.sqs.SqsEventPublisher;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.messaging.AxizMessagingProperties;
import pe.axiz.spring.messaging.ConditionalOnListProperty;
import pe.axiz.spring.messaging.EventPublisherAdapter;
import pe.axiz.spring.messaging.ObservedDispatcher;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Publicadores SQS y clientes propios. */
@AutoConfiguration(beforeName = "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizMessagingSqsClientProperties.class,
  AxizMessagingProperties.class,
  AxizMessagingSqsProperties.class
})
public class AxizMessagingSqsAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  SqsClient axizSqsClient(
      Environment env,
      ObjectProvider<ObservationRegistry> registry,
      AxizMessagingSqsClientProperties aws,
      AwsIdentity identity) {
    return AwsObservability.configured(
        identity.configure(SqsClient.builder(), AwsService.SQS, AwsCapability.MESSAGING),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "sqs",
        aws.apiCallTimeout());
  }

  @Bean
  EventPublisherAdapter axizSqsPublisherAdapter(SqsClient sqs) {
    return new EventPublisherAdapter("sqs", new SqsEventPublisher(sqs));
  }

  @Bean
  @ConditionalOnListProperty("axiz.messaging.sqs.queues")
  SqsEventConsumer axizSqsEventConsumer(
      SqsClient sqs,
      ObservedDispatcher dispatcher,
      AxizMessagingProperties p,
      AxizMessagingSqsProperties sqsProperties) {
    AxizMessagingProperties.Consumer c = p.consumer();
    return new SqsEventConsumer(
        sqs, dispatcher, sqsProperties, Backoff.exponential(c.initialDelay(), c.maxDelay()));
  }
}
