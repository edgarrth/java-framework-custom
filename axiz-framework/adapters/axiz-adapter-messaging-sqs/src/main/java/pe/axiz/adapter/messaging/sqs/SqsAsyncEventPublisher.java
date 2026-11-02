package pe.axiz.adapter.messaging.sqs;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** Sqs publisher using the SDK's native asynchronous transport. */
public final class SqsAsyncEventPublisher implements AsyncEventPublisher {
  private final SqsAsyncClient sqs;

  public SqsAsyncEventPublisher(SqsAsyncClient sqs) {
    this.sqs = Objects.requireNonNull(sqs);
  }

  @Override
  public boolean supports(String destination) {
    return SqsEventPublisher.isDestination(destination);
  }

  @Override
  public CompletionStage<PublishResult> publish(String destination, EventEnvelope event) {

    return Stages.recover(
        Stages.map(
            Stages.call(() -> sqs.sendMessage(SqsEventPublisher.request(destination, event))),
            answer -> new PublishResult(event.id(), destination, answer.messageId())),
        failure -> {
          if (failure instanceof java.util.concurrent.CancellationException cancelled)
            throw cancelled;
          if (failure instanceof Error fatal) throw fatal;
          throw new PublishException(
              "Event " + event.id() + " not accepted by " + destination, failure);
        });
  }
}
