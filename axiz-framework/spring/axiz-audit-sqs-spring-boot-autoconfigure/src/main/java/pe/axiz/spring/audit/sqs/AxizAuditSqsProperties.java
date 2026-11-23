package pe.axiz.spring.audit.sqs;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Destino de auditoría SQS y su política de transporte. */
@ConfigurationProperties("axiz.audit.sqs")
public record AxizAuditSqsProperties(@Nullable String queueUrl) {
  public AxizAuditSqsProperties {
    if (queueUrl != null && (queueUrl.isBlank() || queueUrl.contains("${")))
      throw new IllegalArgumentException(
          "axiz.audit.sqs.queue-url must not be blank or unresolved when configured");
  }
}
