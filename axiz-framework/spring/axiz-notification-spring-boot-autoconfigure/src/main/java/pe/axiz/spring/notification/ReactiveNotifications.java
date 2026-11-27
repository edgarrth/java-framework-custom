package pe.axiz.spring.notification;

import java.util.Objects;
import pe.axiz.core.notification.AsyncNotificationSender;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/**
 * Confirmación nativa de notificaciones, diferida hasta la suscripción y cancelada junto con el
 * invocante.
 */
public final class ReactiveNotifications {
  private final AsyncNotificationSender sender;

  public ReactiveNotifications(AsyncNotificationSender sender) {
    this.sender = Objects.requireNonNull(sender);
  }

  public Mono<Void> send(NotificationRequest request) {
    return ReactiveStages.from(() -> sender.send(request)).then();
  }
}
