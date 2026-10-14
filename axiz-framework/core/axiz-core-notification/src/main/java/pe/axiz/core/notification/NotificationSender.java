package pe.axiz.core.notification;

/** Port to request notifications. */
@FunctionalInterface
public interface NotificationSender {

  void send(NotificationRequest request);
}
