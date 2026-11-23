package pe.axiz.spring.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Selección común del almacenamiento y política de vigencia de las URLs firmadas, independiente del
 * proveedor.
 */
@ConfigurationProperties("axiz.storage")
public record AxizStorageProperties(
    @DefaultValue("s3") String type, @DefaultValue("15m") Duration maxPresignTtl) {
  public AxizStorageProperties {
    if (type == null || type.isBlank())
      throw new IllegalArgumentException("axiz.storage.type is required");
    if (!maxPresignTtl.isPositive())
      throw new IllegalArgumentException("Storage presign lifetime must be positive");
  }
}
