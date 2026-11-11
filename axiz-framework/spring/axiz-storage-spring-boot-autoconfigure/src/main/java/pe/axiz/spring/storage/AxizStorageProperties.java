package pe.axiz.spring.storage;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Object storage ({@code axiz.storage.*}).
 *
 * @param type {@code s3} or {@code memory} (tests and local runs)
 * @param bucket S3 bucket
 * @param kmsKeyId KMS key for SSE-KMS; empty uses the bucket default encryption
 * @param maxPresignTtl longest presigned URL validity
 */
@ConfigurationProperties("axiz.storage")
public record AxizStorageProperties(
    @DefaultValue("s3") String type,
    @Nullable String bucket,
    @Nullable String kmsKeyId,
    @DefaultValue("15m") Duration maxPresignTtl) {
  public AxizStorageProperties {
    if (bucket != null && (bucket.isBlank() || bucket.contains("${")))
      throw new IllegalArgumentException("axiz.storage.bucket must not be blank or unresolved");
  }
}
