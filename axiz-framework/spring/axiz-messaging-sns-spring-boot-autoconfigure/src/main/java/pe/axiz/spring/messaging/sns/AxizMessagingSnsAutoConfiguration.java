package pe.axiz.spring.messaging.sns;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.messaging.sns.SnsEventPublisher;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.messaging.AxizMessagingProperties;
import pe.axiz.spring.messaging.EventPublisherAdapter;
import software.amazon.awssdk.services.sns.SnsClient;

/** SNS publishers and owned clients. */
@AutoConfiguration(beforeName = "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizMessagingSnsClientProperties.class,
  AxizMessagingProperties.class
})
public class AxizMessagingSnsAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  SnsClient axizSnsClient(
      Environment env,
      ObjectProvider<ObservationRegistry> registry,
      AxizMessagingSnsClientProperties aws,
      AwsIdentity identity) {
    return AwsObservability.configured(
        identity.configure(SnsClient.builder(), AwsService.SNS, AwsCapability.MESSAGING),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "sns",
        aws.apiCallTimeout());
  }

  @Bean
  EventPublisherAdapter axizSnsPublisherAdapter(SnsClient sns) {
    return new EventPublisherAdapter("sns", new SnsEventPublisher(sns));
  }
}
