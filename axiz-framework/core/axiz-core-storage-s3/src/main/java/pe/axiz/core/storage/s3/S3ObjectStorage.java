package pe.axiz.core.storage.s3;

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
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Amazon S3 storage: streaming upload and download, SSE-KMS with the configured key, presigned URLs
 * bounded by a maximum TTL. Bucket lifecycle rules are defined with Terraform.
 */
public final class S3ObjectStorage implements ObjectStorage {

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;
  private final @Nullable String kmsKeyId;
  private final Duration maxPresignTtl;

  public S3ObjectStorage(
      S3Client s3,
      S3Presigner presigner,
      String bucket,
      @Nullable String kmsKeyId,
      Duration maxPresignTtl) {
    this.s3 = Objects.requireNonNull(s3);
    this.presigner = Objects.requireNonNull(presigner);
    this.bucket = Objects.requireNonNull(bucket);
    this.kmsKeyId = kmsKeyId;
    this.maxPresignTtl = Objects.requireNonNull(maxPresignTtl);
  }

  @Override
  public void put(String key, InputStream content, ObjectMetadata metadata) {
    if (metadata.contentLength() < 0) {
      throw new IllegalArgumentException("contentLength is required for streaming uploads");
    }
    PutObjectRequest.Builder request =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(ObjectKey.validate(key))
            .contentType(metadata.contentType())
            .contentLength(metadata.contentLength())
            .metadata(metadata.userMetadata());
    if (kmsKeyId != null) {
      request
          .serverSideEncryption(ServerSideEncryption.AWS_KMS)
          .ssekmsKeyId(kmsKeyId)
          .bucketKeyEnabled(true);
    }
    s3.putObject(request.build(), RequestBody.fromInputStream(content, metadata.contentLength()));
  }

  @Override
  public Optional<StoredObject> get(String key) {
    try {
      ResponseInputStream<GetObjectResponse> in =
          s3.getObject(
              GetObjectRequest.builder().bucket(bucket).key(ObjectKey.validate(key)).build());
      GetObjectResponse r = in.response();
      long length = r.contentLength() == null ? -1 : r.contentLength();
      return Optional.of(
          new StoredObject(
              key,
              new ObjectMetadata(
                  r.contentType() == null ? "application/octet-stream" : r.contentType(),
                  length,
                  r.checksumSHA256(),
                  r.metadata()),
              in));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<ObjectMetadata> head(String key) {
    try {
      HeadObjectResponse r =
          s3.headObject(
              HeadObjectRequest.builder().bucket(bucket).key(ObjectKey.validate(key)).build());
      return Optional.of(
          new ObjectMetadata(
              r.contentType() == null ? "application/octet-stream" : r.contentType(),
              r.contentLength() == null ? -1 : r.contentLength(),
              r.checksumSHA256(),
              r.metadata()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(
        DeleteObjectRequest.builder().bucket(bucket).key(ObjectKey.validate(key)).build());
  }

  @Override
  public List<String> list(String prefix, int max) {
    return s3
        .listObjectsV2Paginator(
            ListObjectsV2Request.builder()
                .bucket(bucket)
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

  @Override
  public URL presignPut(String key, String contentType, Duration ttl) {
    PutObjectRequest.Builder put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(ObjectKey.validate(key))
            .contentType(contentType);
    if (kmsKeyId != null) {
      put.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
    }
    return presigner
        .presignPutObject(b -> b.signatureDuration(bounded(ttl)).putObjectRequest(put.build()))
        .url();
  }

  private Duration bounded(Duration ttl) {
    return ttl.compareTo(maxPresignTtl) > 0 ? maxPresignTtl : ttl;
  }
}
