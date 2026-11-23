package pe.axiz.core.notification;

/** Puerto para solicitar notificaciones. */
@FunctionalInterface
public interface NotificationSender {

  void send(NotificationRequest request);
}
