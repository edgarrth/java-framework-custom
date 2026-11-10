package pe.axiz.adapter.storage.s3;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.storage.ObjectKey;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.PresignedUpload;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** Shared wire contract for the imperative and native asynchronous adapters. */
final class S3Requests {
  final String bucket;
  private final @Nullable String kmsKeyId;
  private final Duration maximum;
  private final S3Presigner presigner;

  S3Requests(S3Presigner presigner, String bucket, @Nullable String kmsKeyId, Duration maximum) {
    if (bucket.isBlank()) throw new IllegalArgumentException("S3 bucket is required");
    if (maximum.compareTo(Duration.ofSeconds(1)) < 0 || maximum.compareTo(Duration.ofDays(7)) > 0)
      throw new IllegalArgumentException(
          "S3 maximum presign TTL must be between 1 second and 7 days");
    this.bucket = bucket;
    this.kmsKeyId = kmsKeyId;
    this.maximum = maximum;
    this.presigner = Objects.requireNonNull(presigner);
  }

  PutObjectRequest upload(String key, ObjectMetadata metadata) {
    if (metadata.contentLength() < 0)
      throw new IllegalArgumentException("contentLength is required for streaming uploads");
    var request =
        put(key, metadata.contentType())
            .metadata(metadata.userMetadata())
            .checksumSHA256(metadata.checksumSha256());
    if (kmsKeyId != null) request.bucketKeyEnabled(true);
    return request.build();
  }

  private PutObjectRequest.Builder put(String key, String contentType) {
    var request =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(ObjectKey.validate(key))
            .contentType(contentType);
    if (kmsKeyId != null)
      request.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
    return request;
  }

  URL presignGet(String key, Duration ttl) {
    return presigner
        .presignGetObject(
            b ->
                b.signatureDuration(bounded(ttl))
                    .getObjectRequest(
                        GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(ObjectKey.validate(key))
                            .build()))
        .url();
  }

  URL presignPut(String key, String contentType, Duration ttl) {
    return signedPut(key, contentType, ttl).url();
  }

  PresignedUpload presignUpload(String key, String contentType, Duration ttl) {
    var signed = signedPut(key, contentType, ttl);
    var headers =
        signed.signedHeaders().entrySet().stream()
            .filter(entry -> !entry.getKey().equalsIgnoreCase("host"))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    return new PresignedUpload(URI.create(signed.url().toString()), headers);
  }

  private PresignedPutObjectRequest signedPut(String key, String contentType, Duration ttl) {
    return presigner.presignPutObject(
        b -> b.signatureDuration(bounded(ttl)).putObjectRequest(put(key, contentType).build()));
  }

  private Duration bounded(Duration ttl) {
    if (ttl.compareTo(Duration.ofSeconds(1)) < 0)
      throw new IllegalArgumentException("S3 presign TTL must be at least one second");
    return ttl.compareTo(maximum) > 0 ? maximum : ttl;
  }

  static ObjectMetadata metadata(
      @Nullable String type,
      @Nullable Long length,
      @Nullable String checksum,
      Map<String, String> metadata) {
    return new ObjectMetadata(
        type == null ? "application/octet-stream" : type,
        length == null ? -1 : length,
        checksum,
        metadata);
  }

  static boolean missing(Throwable failure) {
    if (failure instanceof NoSuchKeyException) return true;
    if (!(failure instanceof S3Exception s3) || s3.statusCode() != 404) return false;
    var details = s3.awsErrorDetails();
    String code = details == null ? null : details.errorCode();
    return code == null
        || code.equals("NoSuchKey")
        || code.equals("NotFound")
        || code.equals("404");
  }

  static void validateList(String prefix, int max) {
    if (!prefix.isEmpty()) ObjectKey.validate(prefix);
    if (max < 0)
      throw new IllegalArgumentException("Maximum number of objects must not be negative");
  }
}
