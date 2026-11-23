package pe.axiz.core.messaging;

import java.util.concurrent.CompletionStage;

/** Publicación asíncrona nativa; la finalización exitosa es el acuse del broker. */
public interface AsyncEventPublisher {
  CompletionStage<PublishResult> publish(String destination, EventEnvelope event);

  default boolean supports(String destination) {
    return true;
  }
}
