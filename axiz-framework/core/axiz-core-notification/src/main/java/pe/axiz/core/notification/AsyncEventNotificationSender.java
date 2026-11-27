package pe.axiz.core.notification;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventFactory;

/**
 * Peticiones de notificación nativas con el mismo id de evento, esquema y correlación que el emisor
 * imperativo.
 */
public final class AsyncEventNotificationSender implements AsyncNotificationSender {
  private final EventFactory events;
  private final AsyncEventPublisher publisher;
  private final String destination;

  public AsyncEventNotificationSender(
      EventFactory events, AsyncEventPublisher publisher, String destination) {
    this.events = Objects.requireNonNull(events);
    this.publisher = Objects.requireNonNull(publisher);
    this.destination = Objects.requireNonNull(destination);
  }

  @Override
  public CompletionStage<Void> send(NotificationRequest request) {
    return Stages.ignore(
        Stages.call(
            () -> publisher.publish(destination, NotificationEvents.envelope(events, request))));
  }
}
