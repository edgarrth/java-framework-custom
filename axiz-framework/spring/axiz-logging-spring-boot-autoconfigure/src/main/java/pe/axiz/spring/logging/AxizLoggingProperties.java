package pe.axiz.spring.logging;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Convenciones de logging ({@code axiz.logging.*}).
 *
 * @param json indica si la salida de consola es JSON estructurado; por defecto solo dentro de
 *     Kubernetes
 * @param http logging de solicitudes y respuestas HTTP
 */
@ConfigurationProperties("axiz.logging")
public record AxizLoggingProperties(@Nullable Boolean json, @DefaultValue Http http) {

  /**
   * Log de acceso HTTP.
   *
   * @param enabled indica si se escribe una línea por solicitud (logger {@code axiz.http})
   * @param headers cabeceras permitidas que se incluyen ({@code traceparent} muestra qué traza
   *     envió el invocante); Authorization y Cookie nunca se escriben
   * @param bodies indica si se incluyen los cuerpos JSON (enmascarados y luego truncados; solo
   *     servlet; permitido solo cuando axiz.config.environment es local, test o ci)
   * @param maxBodyLength longitud máxima del cuerpo registrado, aplicada después del
   *     enmascaramiento
   * @param excludedPaths patrones de ruta que no se registran
   * @param maxMaskedBodyLength cuerpo más grande que se conserva para enmascararlo; uno mayor se
   *     registra solo por tipo y tamaño, nunca se corta antes de enmascarar
   */
  public record Http(
      @DefaultValue("true") boolean enabled,
      @DefaultValue({
            "Content-Type",
            "Accept",
            "User-Agent",
            "X-Correlation-Id",
            "X-Request-Id",
            "traceparent"
          })
          List<String> headers,
      @DefaultValue("false") boolean bodies,
      @DefaultValue("4000") int maxBodyLength,
      @DefaultValue({"/actuator/**"}) List<String> excludedPaths,
      @DefaultValue("65536") int maxMaskedBodyLength) {
    public Http {
      excludedPaths = List.copyOf(excludedPaths);
      headers = List.copyOf(headers);
    }
  }
}
