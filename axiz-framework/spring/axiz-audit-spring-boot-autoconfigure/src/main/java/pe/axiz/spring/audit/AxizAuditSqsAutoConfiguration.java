package pe.axiz.spring.audit;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.audit.sqs.SqsAuditPublisher;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SQS sink for audit events, active when {@code axiz.audit.sqs.queue-url} is set. Credentials come
 * from the SDK default chain (IRSA or EKS Pod Identity).
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

  @Bean
  AuditSink axizSqsAuditSink(ObjectProvider<SqsClient> client, AxizAuditProperties properties) {
    SqsClient sqs = client.getIfAvailable(SqsClient::create);
    return new AuditSink(
        "sqs", new SqsAuditPublisher(sqs, Objects.requireNonNull(properties.sqs().queueUrl())));
  }
}
