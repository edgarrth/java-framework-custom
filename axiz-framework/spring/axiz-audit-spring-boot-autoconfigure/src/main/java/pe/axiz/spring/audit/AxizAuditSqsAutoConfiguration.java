package pe.axiz.spring.audit;

import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.audit.sqs.SqsAuditPublisher;
import pe.axiz.core.observability.awssdk.AwsClients;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SQS sink for audit events, active when {@code axiz.audit.sqs.queue-url} is set. Credentials come
 * from the SDK default chain (IRSA or EKS Pod Identity); without an {@link SqsClient} bean the
 * client is built like the messaging one ({@code axiz.aws.region}, {@code axiz.aws.sqs.endpoint} or
 * {@code axiz.aws.endpoint}, observed).
 */
@AutoConfiguration
@ConditionalOnClass(
    name = {
      "pe.axiz.core.audit.sqs.SqsAuditPublisher",
      "software.amazon.awssdk.services.sqs.SqsClient"
    })
@ConditionalOnProperty("axiz.audit.sqs.queue-url")
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizAuditSqsAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  SqsClient axizSqsClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.configured(
        SqsClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "sqs");
  }

  @Bean
  AuditSink axizSqsAuditSink(SqsClient sqs, AxizAuditProperties properties) {
    return new AuditSink(
        "sqs", new SqsAuditPublisher(sqs, Objects.requireNonNull(properties.sqs().queueUrl())));
  }
}
