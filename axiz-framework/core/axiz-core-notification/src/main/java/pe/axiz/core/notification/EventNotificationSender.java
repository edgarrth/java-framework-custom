package pe.axiz.core.notification;

import java.util.Objects;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;

/**
 * Publishes notification requests as {@code notification.requested.v1} events to the delivering
 * service (typically through SNS for fan-out or SQS).
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
