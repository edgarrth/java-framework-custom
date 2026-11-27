package pe.axiz.core.notification;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Petición para notificar a un destinatario con una plantilla. El servicio que entrega es dueño de
 * los proveedores; esta petición viaja como evento.
 *
 * @param id id de la petición (deduplicación)
 * @param channel canal
 * @param recipient dirección, teléfono o token de dispositivo (enmascarado en logs por nombre de
 *     clave)
 * @param templateId identificador de la plantilla
 * @param locale locale de la plantilla
 * @param parameters parámetros de la plantilla (sin secretos)
 * @param correlationId id de correlación
 */
public record NotificationRequest(
    String id,
    NotificationChannel channel,
    String recipient,
    String templateId,
    Locale locale,
    Map<String, String> parameters,
    @Nullable String correlationId) {

  private static final Pattern TEMPLATE = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}$");

  public NotificationRequest {
    Objects.requireNonNull(id);
    Objects.requireNonNull(channel);
    Objects.requireNonNull(recipient);
    Objects.requireNonNull(locale);
    if (templateId == null || !TEMPLATE.matcher(templateId).matches()) {
      throw new IllegalArgumentException("Invalid template id");
    }
    parameters = Map.copyOf(parameters);
  }
}
