package pe.axiz.core.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.storage.ObjectMetadata;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ObjectStorageTest {

  static final class FakeS3 implements S3Client {
    final List<PutObjectRequest> puts = new ArrayList<>();

    @Override
    public PutObjectResponse putObject(PutObjectRequest r, RequestBody body) {
      puts.add(r);
      return PutObjectResponse.builder().build();
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest r) {
      if (r.key().equals("missing")) {
        throw NoSuchKeyException.builder().message("no").build();
      }
      return HeadObjectResponse.builder()
          .contentType("text/plain")
          .contentLength(5L)
          .metadata(Map.of())
          .build();
    }

    @Override
    public String serviceName() {
      return "s3";
    }

    @Override
    public void close() {}
  }

  private static S3Presigner presigner() {
    return S3Presigner.builder()
        .region(Region.US_EAST_1)
        .endpointOverride(URI.create("http://localhost:4566"))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
        .build();
  }

  @Test
  void putUsesSseKmsAndPresignIsBounded() {
    FakeS3 s3 = new FakeS3();
    try (S3Presigner presigner = presigner()) {
      S3ObjectStorage storage =
          new S3ObjectStorage(
              s3, presigner, "axiz-files", "alias/axiz-files", Duration.ofMinutes(15));
      storage.put(
          "in/file.txt", new ByteArrayInputStream(new byte[5]), ObjectMetadata.of("text/plain", 5));
      assertEquals(ServerSideEncryption.AWS_KMS, s3.puts.get(0).serverSideEncryption());
      assertEquals("alias/axiz-files", s3.puts.get(0).ssekmsKeyId());
      assertTrue(storage.head("in/file.txt").isPresent());
      assertTrue(storage.head("missing").isEmpty());
      String url = storage.presignGet("in/file.txt", Duration.ofHours(5)).toString();
      assertTrue(url.contains("X-Amz-Expires=900"), url);
      assertTrue(
          storage
              .presignPut("in/new.txt", "text/plain", Duration.ofMinutes(1))
              .toString()
              .contains("X-Amz-Expires=60"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              storage.put(
                  "../x", new ByteArrayInputStream(new byte[0]), ObjectMetadata.of("t", 0)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              storage.put("x", new ByteArrayInputStream(new byte[0]), ObjectMetadata.of("t", -1)));
    }
  }
}
