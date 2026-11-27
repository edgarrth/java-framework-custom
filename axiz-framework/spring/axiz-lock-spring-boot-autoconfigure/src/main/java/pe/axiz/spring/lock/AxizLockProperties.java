package pe.axiz.spring.lock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Locks distribuidos ({@code axiz.lock.*}) para serializar el trabajo sobre un recurso (p. ej. una
 * cuenta).
 *
 * @param store {@code redis}, {@code dynamodb} o {@code memory} (instancia única, pruebas)
 * @param pollInterval espera entre intentos de adquisición
 */
@ConfigurationProperties("axiz.lock")
public record AxizLockProperties(String store, @DefaultValue("100ms") Duration pollInterval) {
  public AxizLockProperties {
    if (store == null || store.isBlank())
      throw new IllegalArgumentException(
          "axiz.lock.store is required (redis or dynamodb; memory only in local, test or ci)");
  }
}
