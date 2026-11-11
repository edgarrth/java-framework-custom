package pe.axiz.adapter.storage.s3;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.storage.ObjectKey;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.core.storage.PresignedUpload;
import pe.axiz.core.storage.StoredObject;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Amazon S3 storage: streaming upload and download, SSE-KMS with the configured key, presigned URLs
 * bounded by a maximum TTL. Bucket lifecycle rules are defined with Terraform.
 */
public final class S3ObjectStorage implements ObjectStorage {

  private final S3Client s3;
  private final S3Requests requests;

  public S3ObjectStorage(
      S3Client s3,
      S3Presigner presigner,
      String bucket,
      @Nullable String kmsKeyId,
      Duration maxPresignTtl) {
    this.s3 = Objects.requireNonNull(s3);
    this.requests = new S3Requests(presigner, bucket, kmsKeyId, maxPresignTtl);
  }

  @Override
  public void put(String key, InputStream content, ObjectMetadata metadata) {
    s3.putObject(
        requests.upload(key, metadata),
        RequestBody.fromInputStream(content, metadata.contentLength()));
  }

  @Override
  public Optional<StoredObject> get(String key) {
    try {
      ResponseInputStream<GetObjectResponse> in =
          s3.getObject(
              GetObjectRequest.builder()
                  .bucket(requests.bucket)
                  .key(ObjectKey.validate(key))
                  .build());
      GetObjectResponse r = in.response();
      return Optional.of(
          new StoredObject(
              key,
              S3Requests.metadata(
                  r.contentType(), r.contentLength(), r.checksumSHA256(), r.metadata()),
              in));
    } catch (S3Exception e) {
      if (S3Requests.missing(e)) return Optional.empty();
      throw e;
    }
  }

  @Override
  public Optional<ObjectMetadata> head(String key) {
    try {
      HeadObjectResponse r =
          s3.headObject(
              HeadObjectRequest.builder()
                  .bucket(requests.bucket)
                  .key(ObjectKey.validate(key))
                  .build());
      return Optional.of(
          S3Requests.metadata(
              r.contentType(), r.contentLength(), r.checksumSHA256(), r.metadata()));
    } catch (S3Exception e) {
      if (S3Requests.missing(e)) return Optional.empty();
      throw e;
    }
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(
        DeleteObjectRequest.builder().bucket(requests.bucket).key(ObjectKey.validate(key)).build());
  }

  @Override
  public List<String> list(String prefix, int max) {
    S3Requests.validateList(prefix, max);
    if (max == 0) return List.of();
    return s3
        .listObjectsV2Paginator(
            ListObjectsV2Request.builder()
                .bucket(requests.bucket)
                .prefix(prefix)
                .maxKeys(Math.min(max, 1000))
                .build())
        .contents()
        .stream()
        .limit(max)
        .map(S3Object::key)
        .toList();
  }

  @Override
  public URL presignGet(String key, Duration ttl) {
    return requests.presignGet(key, ttl);
  }

  @Override
  public PresignedUpload presignUpload(String key, String contentType, Duration ttl) {
    return requests.presignUpload(key, contentType, ttl);
  }
}
