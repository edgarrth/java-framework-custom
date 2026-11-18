package pe.axiz.spring.lock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Distributed locks ({@code axiz.lock.*}) to serialize work on one resource (e.g. an account).
 *
 * @param store {@code redis}, {@code dynamodb} or {@code memory} (single instance, tests)
 * @param pollInterval wait between acquisition attempts
 */
@ConfigurationProperties("axiz.lock")
public record AxizLockProperties(String store, @DefaultValue("100ms") Duration pollInterval) {
  public AxizLockProperties {
    if (store == null || store.isBlank())
      throw new IllegalArgumentException(
          "axiz.lock.store is required (redis or dynamodb; memory only in local, test or ci)");
  }
}
