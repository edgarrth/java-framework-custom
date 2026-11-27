package pe.axiz.core.notification;

import java.util.Objects;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;

/**
 * Publica peticiones de notificación como eventos {@code notification.requested.v1} hacia el
 * servicio que entrega (normalmente mediante SNS para fan-out, o SQS).
 */
public final class EventNotificationSender implements NotificationSender {

  public static final String EVENT_TYPE = "notification.requested.v1";

  private final EventFactory events;
  private final EventPublisher publisher;
  private final String destination;

  public EventNotificationSender(
      EventFactory events, EventPublisher publisher, String destination) {
    this.events = Objects.requireNonNull(events);
    this.publisher = Objects.requireNonNull(publisher);
    this.destination = Objects.requireNonNull(destination);
  }

  @Override
  public void send(NotificationRequest request) {
    publisher.publish(destination, NotificationEvents.envelope(events, request));
  }
}
