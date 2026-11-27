package pe.axiz.adapter.audit.sqs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.concurrent.Stages;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Aceptación nativa de SQS con los mismos CloudEvents e identidades FIFO que el adaptador
 * imperativo. El cliente del SDK es prestado; su propietario lo cierra al apagar la aplicación.
 */
public final class SqsAsyncAuditPublisher implements AsyncAuditEventPublisher {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SqsAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
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
            Stages.map(
                Stages.call(
                    () -> sqs.sendMessage(SqsAuditPublisher.request(queueUrl, fifo, event))),
                response -> {
                  SqsAuditPublisher.requireAcknowledgement(response);
                  return response;
                })),
        failure -> {
          if (failure instanceof CancellationException cancelled) throw cancelled;
          if (failure instanceof Error fatal) throw fatal;
          throw new AuditPublishException(
              "Audit event " + event.id() + " not accepted by SQS", failure);
        });
  }
}
