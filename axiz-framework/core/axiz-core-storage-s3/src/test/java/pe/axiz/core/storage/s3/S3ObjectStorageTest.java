package pe.axiz.core.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import pe.axiz.core.storage.StoredObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
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

    final List<String> deleted = new ArrayList<>();
    final List<ListObjectsV2Request> listings = new ArrayList<>();

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest r) {
      if (r.key().equals("missing")) {
        throw NoSuchKeyException.builder().message("no").build();
      }
      if (r.key().equals("bare")) {
        // S3 compatible stores may omit the type and the length
        return HeadObjectResponse.builder().metadata(Map.of()).build();
      }
      return HeadObjectResponse.builder()
          .contentType("text/plain")
          .contentLength(5L)
          .metadata(Map.of())
          .build();
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest r) {
      if (r.key().equals("missing")) {
        throw NoSuchKeyException.builder().message("no").build();
      }
      GetObjectResponse.Builder response =
          GetObjectResponse.builder().metadata(Map.of("origin", "test")).checksumSHA256("c2hh");
      if (!r.key().equals("bare")) {
        response.contentType("text/csv").contentLength(3L);
      }
      return new ResponseInputStream<>(
          response.build(), AbortableInputStream.create(new ByteArrayInputStream(new byte[] {1, 2, 3})));
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest r) {
      deleted.add(r.bucket() + "/" + r.key());
      return DeleteObjectResponse.builder().build();
    }

    @Override
    public ListObjectsV2Response listObjectsV2(ListObjectsV2Request r) {
      listings.add(r);
      // two pages of two keys
      if (r.continuationToken() == null) {
        return ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("in/a").build(), S3Object.builder().key("in/b").build())
            .isTruncated(true)
            .nextContinuationToken("page-2")
            .build();
      }
      return ListObjectsV2Response.builder()
          .contents(S3Object.builder().key("in/c").build(), S3Object.builder().key("in/d").build())
          .isTruncated(false)
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

  @Test
  void getStreamsTheObjectWithItsMetadata() throws Exception {
    FakeS3 s3 = new FakeS3();
    try (S3Presigner presigner = presigner()) {
      S3ObjectStorage storage =
          new S3ObjectStorage(s3, presigner, "axiz-files", null, Duration.ofMinutes(15));
      StoredObject found = storage.get("in/file.csv").orElseThrow();
      assertEquals("in/file.csv", found.key());
      assertEquals("text/csv", found.metadata().contentType());
      assertEquals(3, found.metadata().contentLength());
      assertEquals(Map.of("origin", "test"), found.metadata().userMetadata());
      try (var in = found.content()) {
        assertArrayEquals(new byte[] {1, 2, 3}, in.readAllBytes());
      }
      StoredObject bare = storage.get("bare").orElseThrow();
      assertEquals("application/octet-stream", bare.metadata().contentType());
      assertEquals(-1, bare.metadata().contentLength());
      bare.content().close();
      assertTrue(storage.get("missing").isEmpty());

      ObjectMetadata head = storage.head("bare").orElseThrow();
      assertEquals("application/octet-stream", head.contentType());
      assertEquals(-1, head.contentLength());
    }
  }

  @Test
  void withoutAKmsKeyTheBucketDefaultEncryptionApplies() {
    FakeS3 s3 = new FakeS3();
    try (S3Presigner presigner = presigner()) {
      S3ObjectStorage storage =
          new S3ObjectStorage(s3, presigner, "axiz-files", null, Duration.ofMinutes(15));
      storage.put("in/x.txt", new ByteArrayInputStream(new byte[2]), ObjectMetadata.of("text/plain", 2));
      assertNull(s3.puts.get(0).serverSideEncryption());
      assertNull(s3.puts.get(0).ssekmsKeyId());
      assertEquals(2L, s3.puts.get(0).contentLength());
      String url = storage.presignPut("in/y.txt", "text/plain", Duration.ofMinutes(1)).toString();
      assertTrue(!url.contains("x-amz-server-side-encryption"), url);
    }
  }

  @Test
  void deleteAndListWorkOnTheConfiguredBucket() {
    FakeS3 s3 = new FakeS3();
    try (S3Presigner presigner = presigner()) {
      S3ObjectStorage storage =
          new S3ObjectStorage(s3, presigner, "axiz-files", null, Duration.ofMinutes(15));
      storage.delete("in/old.txt");
      assertEquals(List.of("axiz-files/in/old.txt"), s3.deleted);
      assertThrows(IllegalArgumentException.class, () -> storage.delete("../etc"));

      // the listing follows the pages and stops at the maximum
      assertEquals(List.of("in/a", "in/b", "in/c"), storage.list("in/", 3));
      assertEquals("in/", s3.listings.get(0).prefix());
      assertEquals(3, s3.listings.get(0).maxKeys());
      assertEquals("page-2", s3.listings.get(1).continuationToken());
      s3.listings.clear();
      storage.list("in/", 5000);
      assertEquals(1000, s3.listings.get(0).maxKeys(), "S3 returns at most 1000 keys per page");
    }
  }
}
