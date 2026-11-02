package pe.axiz.core.notification;

import java.util.concurrent.CompletionStage;

/**
 * Requests notification delivery through native asynchronous publication. Completion acknowledges
 * the broker, not delivery to a recipient. The delivery service owns provider retries and
 * deduplication.
 */
@FunctionalInterface
public interface AsyncNotificationSender {
  CompletionStage<Void> send(NotificationRequest request);
}
