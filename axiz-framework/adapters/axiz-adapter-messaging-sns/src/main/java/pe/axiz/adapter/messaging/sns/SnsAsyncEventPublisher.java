package pe.axiz.adapter.messaging.sns;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.BoundedPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishBound;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

/** Sns publisher using the SDK's native asynchronous transport. */
public final class SnsAsyncEventPublisher implements AsyncEventPublisher, BoundedPublisher {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SnsAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final SnsAsyncClient sns;

  public SnsAsyncEventPublisher(SnsAsyncClient sns) {
    this.sns = Objects.requireNonNull(sns);
  }

  @Override
  public PublishBound publishBound() {
    try {
      var configuration = sns.serviceClientConfiguration();
      if (configuration == null) return PublishBound.unbounded();
      return configuration
          .overrideConfiguration()
          .apiCallTimeout()
          .map(PublishBound::of)
          .orElseGet(PublishBound::unbounded);
    } catch (UnsupportedOperationException unavailable) {
      return PublishBound.unbounded();
    }
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
          if (failure instanceof CancellationException cancelled) throw cancelled;
          if (failure instanceof Error fatal) throw fatal;
          throw new PublishException(
              "Event " + event.id() + " not accepted by " + destination, failure);
        });
  }
}
