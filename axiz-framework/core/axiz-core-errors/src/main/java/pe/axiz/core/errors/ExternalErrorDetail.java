package pe.axiz.core.errors;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * Lo que respondió una dependencia al fallar. Se conserva para logs, trazas y (opcionalmente)
 * respuestas; un cuerpo vacío de la dependencia se preserva como tal en lugar de perderse.
 *
 * @param system nombre lógico de la dependencia (nombre del cliente)
 * @param status estado HTTP o -1 para errores de transporte
 * @param code código de error informado por la dependencia, si existe
 * @param message mensaje informado por la dependencia, si existe
 * @param body cuerpo en bruto (truncado a {@link #MAX_BODY}), puede estar vacío pero nunca es null
 */
public record ExternalErrorDetail(
    String system, int status, @Nullable String code, @Nullable String message, String body)
    implements Serializable {

  public static final int MAX_BODY = 2048;

  public ExternalErrorDetail {
    body = body == null ? "" : (body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body);
  }

  public boolean emptyBody() {
    return body.isEmpty();
  }

  public boolean transportError() {
    return status < 0;
  }
}
