package pe.axiz.adapter.audit.sqs;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.commons.Stages;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Native SQS acceptance with the same CloudEvents and FIFO identities as the imperative adapter.
 * The SDK client is borrowed; its owner closes it at application shutdown.
 */
public final class SqsAsyncAuditPublisher implements AsyncAuditEventPublisher {
  private final SqsAsyncClient sqs;
  private final String queueUrl;
  private final boolean fifo;

  public SqsAsyncAuditPublisher(SqsAsyncClient sqs, String queueUrl) {
    this.sqs = Objects.requireNonNull(sqs);
    this.queueUrl = Objects.requireNonNull(queueUrl);
    if (queueUrl.isBlank())
      throw new IllegalArgumentException("Audit SQS queue URL must not be blank");
    this.fifo = queueUrl.endsWith(".fifo");
  }

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
    return Stages.settle(
        Stages.ignore(
            Stages.call(() -> sqs.sendMessage(SqsAuditPublisher.request(queueUrl, fifo, event)))),
        failure -> {
          if (failure instanceof CancellationException cancelled) throw cancelled;
          if (failure instanceof Error fatal) throw fatal;
          throw new AuditPublishException(
              "Audit event " + event.id() + " not accepted by SQS", failure);
        });
  }
}
