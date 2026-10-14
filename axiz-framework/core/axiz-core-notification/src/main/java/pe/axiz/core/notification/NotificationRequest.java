package pe.axiz.core.notification;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Request to notify a recipient with a template. The delivering service owns the providers; this
 * request travels as an event.
 *
 * @param id request id (deduplication)
 * @param channel channel
 * @param recipient address, phone or device token (masked in logs by key name)
 * @param templateId template identifier
 * @param locale locale of the template
 * @param parameters template parameters (no secrets)
 * @param correlationId correlation id
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
