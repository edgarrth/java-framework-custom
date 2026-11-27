package pe.axiz.adapter.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.StoredObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
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
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ObjectStorageTest {

  static final class FakeS3 implements S3Client {
    final List<PutObjectRequest> puts = new ArrayList<>();
    final List<Long> contentLengths = new ArrayList<>();

    @Override
    public PutObjectResponse putObject(PutObjectRequest r, RequestBody body) {
      puts.add(r);
      contentLengths.add(body.optionalContentLength().orElseThrow());
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
        // los almacenes compatibles con S3 pueden omitir el tipo y la longitud
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
          response.build(),
          AbortableInputStream.create(new ByteArrayInputStream(new byte[] {1, 2, 3})));
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest r) {
      deleted.add(r.bucket() + "/" + r.key());
      return DeleteObjectResponse.builder().build();
    }

    @Override
    public ListObjectsV2Response listObjectsV2(ListObjectsV2Request r) {
      listings.add(r);
      // dos páginas de dos claves
      if (r.continuationToken() == null) {
        return ListObjectsV2Response.builder()
            .contents(
                S3Object.builder().key("in/a").build(), S3Object.builder().key("in/b").build())
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
              .presignUpload("in/new.txt", "text/plain", Duration.ofMinutes(1))
              .url()
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
      storage.put(
          "in/x.txt", new ByteArrayInputStream(new byte[2]), ObjectMetadata.of("text/plain", 2));
      assertNull(s3.puts.get(0).serverSideEncryption());
      assertNull(s3.puts.get(0).ssekmsKeyId());
      assertEquals(2L, s3.contentLengths.get(0));
      String url =
          storage.presignUpload("in/y.txt", "text/plain", Duration.ofMinutes(1)).url().toString();
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

      // el listado sigue las páginas y se detiene en el máximo
      assertEquals(List.of("in/a", "in/b", "in/c"), storage.list("in/", 3));
      assertEquals("in/", s3.listings.get(0).prefix());
      assertEquals(3, s3.listings.get(0).maxKeys());
      assertEquals("page-2", s3.listings.get(1).continuationToken());
      s3.listings.clear();
      assertEquals(List.of(), storage.list("", 0));
      assertTrue(s3.listings.isEmpty());
      assertThrows(IllegalArgumentException.class, () -> storage.list("", -1));
      assertThrows(IllegalArgumentException.class, () -> storage.presignGet("x", Duration.ZERO));
      storage.list("in/", 5000);
      assertEquals(1000, s3.listings.get(0).maxKeys(), "S3 returns at most 1000 keys per page");
    }
  }

  @Test
  void checksumAndHead404AreConsistentWithoutHidingBucketErrors() {
    S3Client client = Mockito.mock(S3Client.class);
    Mockito.when(client.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).build())
        .thenThrow(
            S3Exception.builder()
                .statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                .build());
    try (var signer = presigner()) {
      var storage = new S3ObjectStorage(client, signer, "files", null, Duration.ofMinutes(1));
      assertTrue(storage.head("x").isEmpty());
      assertThrows(S3Exception.class, () -> storage.head("x"));
      var local = new FakeS3();
      new S3ObjectStorage(local, signer, "files", null, Duration.ofMinutes(1))
          .put(
              "x",
              new ByteArrayInputStream(new byte[] {1}),
              new ObjectMetadata("t", 1, "checksum", Map.of()));
      assertEquals("checksum", local.puts.getFirst().checksumSHA256());
    }
  }

  @Test
  void presignedUploadsIncludeEverySignedHeaderAndPreserveSsePolicy() {
    try (var signer = presigner()) {
      var storage =
          new S3ObjectStorage(new FakeS3(), signer, "files", "alias/files", Duration.ofMinutes(15));
      var upload = storage.presignUpload("in/x", "text/plain", Duration.ofMinutes(1));
      assertEquals(List.of("text/plain"), upload.headers().get("content-type"));
      assertEquals(List.of("aws:kms"), upload.headers().get("x-amz-server-side-encryption"));
      assertEquals(
          List.of("alias/files"),
          upload.headers().get("x-amz-server-side-encryption-aws-kms-key-id"));
      assertTrue(!upload.headers().containsKey("host"));
      assertTrue(!upload.headers().containsKey("x-amz-server-side-encryption-bucket-key-enabled"));
      String signed =
          Arrays.stream(upload.url().getRawQuery().split("&"))
              .filter(value -> value.startsWith("X-Amz-SignedHeaders="))
              .map(
                  value ->
                      URLDecoder.decode(
                          value.substring("X-Amz-SignedHeaders=".length()), StandardCharsets.UTF_8))
              .findFirst()
              .orElseThrow();
      var expected = new HashSet<>(List.of(signed.split(";")));
      expected.remove("host");
      assertEquals(expected, upload.headers().keySet());
      String url =
          storage.presignUpload("in/x", "text/plain", Duration.ofMinutes(1)).url().toString();
      assertTrue(!url.contains("bucket-key-enabled"));
    }
  }
}
