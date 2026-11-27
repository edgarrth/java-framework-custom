package pe.axiz.core.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;

/** Una única política de red e identidad para ambos emisores de notificaciones. */
final class NotificationEvents {
  private NotificationEvents() {}

  static EventEnvelope envelope(EventFactory events, NotificationRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", request.id());
    payload.put("channel", request.channel().name());
    payload.put("recipient", request.recipient());
    payload.put("templateId", request.templateId());
    payload.put("locale", request.locale().toLanguageTag());
    payload.put("parameters", request.parameters());
    EventEnvelope e =
        events.create(
            request.id(), EventNotificationSender.EVENT_TYPE, request.id(), payload, null);
    String correlationId = request.correlationId();
    if (correlationId != null) {
      e = e.withExtension(EventEnvelope.CORRELATION_ID, correlationId);
    }
    return e;
  }
}
