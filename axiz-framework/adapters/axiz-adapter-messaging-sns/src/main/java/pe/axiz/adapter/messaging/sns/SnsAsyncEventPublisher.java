package pe.axiz.adapter.messaging.sns;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

/** Sns publisher using the SDK's native asynchronous transport. */
public final class SnsAsyncEventPublisher implements AsyncEventPublisher {
  private final SnsAsyncClient sns;

  public SnsAsyncEventPublisher(SnsAsyncClient sns) {
    this.sns = Objects.requireNonNull(sns);
  }

  @Override
  public boolean supports(String destination) {
    return destination.startsWith("arn:") && destination.contains(":sns:");
  }

  @Override
  public CompletionStage<PublishResult> publish(String destination, EventEnvelope event) {

    return Stages.recover(
        Stages.map(
            Stages.call(() -> sns.publish(SnsEventPublisher.request(destination, event))),
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
