package pe.axiz.spring.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Idempotencia de la API ({@code axiz.idempotency.*}) para operaciones anotadas con
 * {@code @Idempotent}.
 *
 * @param store {@code redis}, {@code dynamodb} o {@code memory} (instancia única, solo pruebas)
 * @param inProgressTtl tiempo que una clave permanece bloqueada mientras se ejecuta la primera
 *     solicitud
 * @param retention tiempo durante el cual se reproducen las respuestas completadas
 * @param maxBodySize cuerpo de solicitud más grande al que se calcula la huella (las solicitudes
 *     mayores se rechazan con 413)
 * @param maxResponseSize cuerpo de respuesta más grande que se almacena para reproducción; una
 *     respuesta mayor igualmente se devuelve y se almacenan su estado y sus cabeceras, pero una
 *     reproducción no lleva cuerpo y lleva la cabecera {@code Idempotent-Body-Omitted: true}
 */
@ConfigurationProperties("axiz.idempotency")
public record AxizIdempotencyProperties(
    @DefaultValue("memory") String store,
    @DefaultValue("30s") Duration inProgressTtl,
    @DefaultValue("24h") Duration retention,
    @DefaultValue("1048576") int maxBodySize,
    @DefaultValue("1048576") int maxResponseSize,
    @DefaultValue("23h") Duration clientRetryWindow) {
  public AxizIdempotencyProperties {
    if (!inProgressTtl.isPositive()
        || !retention.isPositive()
        || clientRetryWindow.isNegative()
        || retention.compareTo(clientRetryWindow.plus(inProgressTtl)) < 0) {
      throw new IllegalArgumentException(
          "axiz.idempotency.retention="
              + retention
              + " must be >= client-retry-window="
              + clientRetryWindow
              + " + in-progress-ttl="
              + inProgressTtl);
    }
    if (maxBodySize <= 0 || maxResponseSize <= 0)
      throw new IllegalArgumentException("idempotency body limits must be positive");
  }
}
