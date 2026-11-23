package pe.axiz.spring.storage.s3;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** La configuración exclusiva de S3 conserva las claves públicas axiz.storage de bucket y KMS. */
@ConfigurationProperties("axiz.storage")
public record AxizStorageS3Properties(@Nullable String bucket, @Nullable String kmsKeyId) {
  public AxizStorageS3Properties {
    if (bucket != null && (bucket.isBlank() || bucket.contains("${")))
      throw new IllegalArgumentException("axiz.storage.bucket must not be blank or unresolved");
  }

  String requireBucket() {
    if (bucket == null)
      throw new IllegalArgumentException("axiz.storage.bucket is required by the S3 integration");
    return bucket;
  }
}
