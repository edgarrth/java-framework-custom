package pe.axiz.spring.messaging.msk;

import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.adapter.kafka.msk.AxizIamClientCallbackHandler;
import pe.axiz.adapter.kafka.msk.MskIam;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.messaging.kafka.KafkaClientAuthentication;

/** Explicit MSK IAM authentication, using the shared per-capability AWS identity owner. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.messaging.kafka.AxizMessagingKafkaAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "axiz.messaging.kafka.authentication", havingValue = "msk")
@EnableConfigurationProperties(AxizMessagingMskProperties.class)
public class AxizMessagingMskAutoConfiguration {
  @Bean
  KafkaClientAuthentication axizMskAuthentication(
      AxizMessagingMskProperties properties, AwsIdentity identity) {
    if (!properties.iam())
      throw new IllegalArgumentException(
          "MSK authentication requires axiz.messaging.kafka.iam=true");
    return new KafkaClientAuthentication() {
      @Override
      public String name() {
        return "msk";
      }

      @Override
      public void configure(Map<String, Object> configured) {
        configured.putAll(MskIam.clientProperties());
        configured.put(
            AxizIamClientCallbackHandler.CREDENTIALS_SOURCE_FACTORY_CONFIG,
            identity.factory(AwsService.MSK, AwsCapability.MESSAGING));
      }
    };
  }
}
