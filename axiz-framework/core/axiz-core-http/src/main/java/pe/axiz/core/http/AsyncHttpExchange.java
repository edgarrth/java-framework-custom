package pe.axiz.core.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Intercambio HTTP nativo acotado. Las implementaciones deben resolver DNS, conectar y consumir el
 * cuerpo sin bloquear, aplicar el límite de bytes y el timeout de la petición, propagar la
 * cancelación y nunca seguir redirecciones. La aplicación es dueña del arranque y el apagado del
 * transporte.
 */
@FunctionalInterface
public interface AsyncHttpExchange {
  CompletionStage<Response> exchange(Request request, long maxResponseBytes);

  /**
   * Datos de red inmutables; los diagnósticos nunca exponen la URI, las credenciales ni el cuerpo.
   */
  record Request(
      URI uri,
      String method,
      @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL)
          Map<String, List<String>> headers,
      @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL) String body,
      Duration timeout) {
    public Request {
      Objects.requireNonNull(uri);
      Objects.requireNonNull(method);
      Objects.requireNonNull(body);
      Objects.requireNonNull(timeout);
      if (!timeout.isPositive())
        throw new IllegalArgumentException("HTTP timeout must be positive");
      var copy = new LinkedHashMap<String, List<String>>();
      headers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
      headers = Map.copyOf(copy);
    }

    @Override
    public String toString() {
      return "Request[redacted]";
    }
  }

  /**
   * Los payloads de token siguen siendo accesibles de forma explícita y nunca se incluyen en los
   * diagnósticos.
   */
  record Response(
      int status,
      @Nullable String contentType,
      @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL) String body) {
    public Response {
      Objects.requireNonNull(body);
    }

    @Override
    public String toString() {
      return "Response[status=" + status + "]";
    }
  }
}
