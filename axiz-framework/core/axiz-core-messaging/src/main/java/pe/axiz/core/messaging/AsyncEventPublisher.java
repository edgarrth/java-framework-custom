package pe.axiz.core.messaging;

import java.util.concurrent.CompletionStage;

/** Native asynchronous publication; successful completion is the broker acknowledgement. */
public interface AsyncEventPublisher {
  CompletionStage<PublishResult> publish(String destination, EventEnvelope event);

  default boolean supports(String destination) {
    return true;
  }
}
