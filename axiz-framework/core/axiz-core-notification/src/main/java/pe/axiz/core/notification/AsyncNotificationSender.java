package pe.axiz.core.notification;

import java.util.concurrent.CompletionStage;

/**
 * Solicita la entrega de notificaciones mediante publicación asíncrona nativa. La finalización
 * confirma el broker, no la entrega a un destinatario. El servicio de entrega es dueño de los
 * reintentos y la deduplicación del proveedor.
 */
@FunctionalInterface
public interface AsyncNotificationSender {
  CompletionStage<Void> send(NotificationRequest request);
}
