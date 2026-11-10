package pe.axiz.adapter.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.storage.ObjectMetadata;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.ResponsePublisher;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
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

class S3AsyncObjectStorageTest {
  @Test
  void paginationKeepsItsSubmittingContextAndRestoresTheSdkCompletionWorker() throws Exception {
    var local = new ThreadLocal<String>();
    var client = mock(S3AsyncClient.class);
    var first = new CompletableFuture<ListObjectsV2Response>();
    var calls = new AtomicInteger();
    when(client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenAnswer(
            invocation -> {
              assertEquals("request", local.get());
              if (calls.incrementAndGet() == 1) return first;
              return CompletableFuture.completedFuture(
                  ListObjectsV2Response.builder()
                      .contents(S3Object.builder().key("b").build())
                      .isTruncated(false)
                      .build());
            });
    Stages.ContextProvider provider =
        () -> {
          String captured = local.get();
          return () -> {
            String previous = local.get();
            local.set(captured);
            return () -> {
              if (previous == null) local.remove();
              else local.set(previous);
            };
          };
        };
    try (var signer = presigner();
        var worker = Executors.newSingleThreadExecutor()) {
      var storage = new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofMinutes(15));
      CompletableFuture<List<String>> result;
      local.set("request");
      try (var scope = Stages.context(provider)) {
        result = storage.list("", 2).toCompletableFuture();
      }
      local.remove();
      worker
          .submit(
              () -> {
                local.set("worker");
                first.complete(
                    ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("a").build())
                        .isTruncated(true)
                        .nextContinuationToken("next")
                        .build());
                assertEquals("worker", local.get());
                local.remove();
              })
          .get(5, TimeUnit.SECONDS);
      assertEquals(List.of("a", "b"), result.get(5, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
    } finally {
      local.remove();
    }
  }

  @Test
  void failedContextCaptureCancelsThePendingNativeListing() {
    var client = mock(S3AsyncClient.class);
    var pending = new CompletableFuture<ListObjectsV2Response>();
    when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(pending);
    var failure = new IllegalStateException("context unavailable");
    try (var signer = presigner();
        var scope =
            Stages.context(
                () -> {
                  throw failure;
                })) {
      var storage = new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofMinutes(15));
      var result = storage.list("", 2).toCompletableFuture();
      assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
      assertTrue(pending.isCancelled());
    }
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
  void uploadUsesTheSameWireContractAndCancellationReachesTheNativeRequestAndContent() {
    S3AsyncClient client = mock(S3AsyncClient.class);
    AtomicReference<AsyncRequestBody> body = new AtomicReference<>();
    AtomicReference<PutObjectRequest> request = new AtomicReference<>();
    var pending = new CompletableFuture<PutObjectResponse>();
    when(client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
        .thenAnswer(
            call -> {
              request.set(call.getArgument(0));
              body.set(call.getArgument(1));
              return pending;
            });
    AtomicReference<Flow.Subscriber<? super ByteBuffer>> source = new AtomicReference<>();
    AtomicLong demand = new AtomicLong();
    AtomicInteger cancellations = new AtomicInteger();
    try (var signer = presigner()) {
      var storage =
          new S3AsyncObjectStorage(client, signer, "files", "alias/files", Duration.ofMinutes(15));
      var result =
          storage
              .put(
                  "in/x",
                  target -> {
                    source.set(target);
                    target.onSubscribe(
                        new Flow.Subscription() {
                          @Override
                          public void request(long n) {
                            demand.addAndGet(n);
                          }

                          @Override
                          public void cancel() {
                            cancellations.incrementAndGet();
                          }
                        });
                  },
                  new ObjectMetadata("text/plain", 2, "checksum", Map.of("origin", "test")))
              .toCompletableFuture();
      assertEquals("files", request.get().bucket());
      assertEquals("in/x", request.get().key());
      assertEquals("checksum", request.get().checksumSHA256());
      assertEquals(ServerSideEncryption.AWS_KMS, request.get().serverSideEncryption());
      assertEquals(Boolean.TRUE, request.get().bucketKeyEnabled());
      assertEquals(Optional.of(2L), body.get().contentLength());
      var sink = new Probe();
      body.get().subscribe(sink);
      assertEquals(0, demand.get());
      sink.subscription.get().request(1);
      assertEquals(1, demand.get());
      ByteBuffer mutable = ByteBuffer.wrap(new byte[] {1, 2});
      source.get().onNext(mutable);
      mutable.put(0, (byte) 9);
      assertEquals(1, sink.value.get().get(0));
      assertTrue(sink.value.get().isReadOnly());
      assertTrue(result.cancel(true));
      assertTrue(pending.isCancelled());
      assertTrue(cancellations.get() > 0);
    }
  }

  @Test
  void downloadHonorsDemandOwnsChunksAndAbortsUnconsumedOrCancelledBodies() {
    S3AsyncClient client = mock(S3AsyncClient.class);
    AtomicReference<Subscriber<? super ByteBuffer>> source = new AtomicReference<>();
    AtomicInteger cancelled = new AtomicInteger();
    AtomicLong demand = new AtomicLong();
    SdkPublisher<ByteBuffer> body =
        target -> {
          source.set(target);
          target.onSubscribe(
              new Subscription() {
                @Override
                public void request(long n) {
                  demand.addAndGet(n);
                }

                @Override
                public void cancel() {
                  cancelled.incrementAndGet();
                }
              });
        };
    when(client.getObject(
            any(GetObjectRequest.class),
            ArgumentMatchers
                .<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>
                    any()))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    new ResponsePublisher<>(
                        GetObjectResponse.builder()
                            .contentType("text/plain")
                            .contentLength(2L)
                            .metadata(Map.of("x", "y"))
                            .build(),
                        body)));
    try (var signer = presigner()) {
      var storage = new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofMinutes(15));
      var unopened = storage.get("x").toCompletableFuture().join().orElseThrow();
      unopened.close();
      unopened.close();
      assertEquals(1, cancelled.get());
      var object = storage.get("x").toCompletableFuture().join().orElseThrow();
      assertEquals(2, object.metadata().contentLength());
      assertEquals(Map.of("x", "y"), object.metadata().userMetadata());
      var probe = new FlowProbe();
      object.content().subscribe(probe);
      assertEquals(0, demand.get());
      probe.subscription.get().request(1);
      assertEquals(1, demand.get());
      ByteBuffer chunk = ByteBuffer.wrap(new byte[] {3, 4});
      source.get().onNext(chunk);
      chunk.put(0, (byte) 9);
      assertEquals(3, probe.value.get().get(0));
      probe.subscription.get().cancel();
      assertEquals(2, cancelled.get());
      var second = new FlowProbe();
      object.content().subscribe(second);
      assertInstanceOf(IllegalStateException.class, second.failure.get());
      var completed = storage.get("x").toCompletableFuture().join().orElseThrow();
      var completedProbe = new FlowProbe();
      completed.content().subscribe(completedProbe);
      source.get().onComplete();
      assertEquals(1, completedProbe.completed.get());
      var failed = storage.get("x").toCompletableFuture().join().orElseThrow();
      var failedProbe = new FlowProbe();
      failed.content().subscribe(failedProbe);
      var error = new IllegalStateException("stream");
      source.get().onError(error);
      assertSame(error, failedProbe.failure.get());
    }
  }

  @Test
  void errorsMissingObjectsAndPresigningAreHandledWithoutCallingTheSyncClient() {
    S3AsyncClient client = mock(S3AsyncClient.class);
    when(client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(S3Exception.builder().statusCode(404).build()))
        .thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().build()))
        .thenReturn(CompletableFuture.failedFuture(S3Exception.builder().statusCode(403).build()));
    var get = new CompletableFuture<ResponsePublisher<GetObjectResponse>>();
    when(client.getObject(
            any(GetObjectRequest.class),
            ArgumentMatchers
                .<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>
                    any()))
        .thenReturn(CompletableFuture.failedFuture(NoSuchKeyException.builder().build()))
        .thenReturn(get);
    when(client.deleteObject(any(DeleteObjectRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(DeleteObjectResponse.builder().build()));
    try (var signer = presigner()) {
      var storage = new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofMinutes(15));
      assertTrue(storage.head("x").toCompletableFuture().join().isEmpty());
      assertEquals(
          -1, storage.head("x").toCompletableFuture().join().orElseThrow().contentLength());
      assertThrows(CompletionException.class, () -> storage.head("x").toCompletableFuture().join());
      assertTrue(storage.get("x").toCompletableFuture().join().isEmpty());
      assertTrue(storage.get("x").toCompletableFuture().cancel(true));
      assertTrue(get.isCancelled());
      storage.delete("x").toCompletableFuture().join();
      assertTrue(
          storage
              .presignGet("x", Duration.ofHours(3))
              .toCompletableFuture()
              .join()
              .toString()
              .contains("X-Amz-Expires=900"));
      assertTrue(
          storage
              .presignPut("x", "text/plain", Duration.ofMinutes(1))
              .toCompletableFuture()
              .join()
              .toString()
              .contains("X-Amz-Expires=60"));
      var upload =
          storage
              .presignUpload("x", "text/plain", Duration.ofMinutes(1))
              .toCompletableFuture()
              .join();
      assertEquals(List.of("text/plain"), upload.headers().get("content-type"));
      assertThrows(
          CompletionException.class,
          () -> storage.presignGet("x", Duration.ZERO).toCompletableFuture().join());
      assertThrows(
          CompletionException.class,
          () ->
              storage
                  .put("../x", target -> {}, ObjectMetadata.of("t", 0))
                  .toCompletableFuture()
                  .join());
      assertThrows(
          IllegalArgumentException.class,
          () -> new S3AsyncObjectStorage(client, signer, "", null, Duration.ofMinutes(1)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofDays(8)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new S3AsyncObjectStorage(client, signer, "files", null, Duration.ZERO));
      assertEquals(List.of(), storage.list("", 0).toCompletableFuture().join());
      assertThrows(
          CompletionException.class, () -> storage.list("", -1).toCompletableFuture().join());
    }
  }

  @Test
  void asyncPaginationStopsAtTheBoundAndCancellationCancelsTheActivePage() {
    S3AsyncClient client = mock(S3AsyncClient.class);
    when(client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenAnswer(
            call -> {
              ListObjectsV2Request request = call.getArgument(0);
              return CompletableFuture.completedFuture(
                  ListObjectsV2Response.builder()
                      .contents(
                          S3Object.builder()
                              .key(request.continuationToken() == null ? "a" : "b")
                              .build())
                      .isTruncated(request.continuationToken() == null)
                      .nextContinuationToken("second")
                      .build());
            });
    try (var signer = presigner()) {
      var storage = new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofMinutes(15));
      assertEquals(List.of("a", "b"), storage.list("", 2).toCompletableFuture().join());
      assertEquals(List.of("a"), storage.list("", 1).toCompletableFuture().join());
      var pending = new CompletableFuture<ListObjectsV2Response>();
      when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(pending);
      var listing = storage.list("", 4).toCompletableFuture();
      assertTrue(listing.cancel(true));
      assertTrue(pending.isCancelled());
    }
  }

  @Test
  void alreadyCompletedPagesDoNotOverflowTheStackAndInvalidContinuationsFail() {
    S3AsyncClient client = mock(S3AsyncClient.class);
    when(client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenAnswer(
            call -> {
              ListObjectsV2Request request = call.getArgument(0);
              int index =
                  request.continuationToken() == null
                      ? 0
                      : Integer.parseInt(request.continuationToken());
              return CompletableFuture.completedFuture(
                  ListObjectsV2Response.builder()
                      .contents(S3Object.builder().key("x/" + index).build())
                      .isTruncated(index < 9999)
                      .nextContinuationToken(Integer.toString(index + 1))
                      .build());
            });
    try (var signer = presigner()) {
      var storage = new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofMinutes(1));
      assertEquals(10000, storage.list("x/", 20000).toCompletableFuture().join().size());
      when(client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(
              CompletableFuture.completedFuture(
                  ListObjectsV2Response.builder().isTruncated(true).build()));
      assertInstanceOf(
          IllegalStateException.class,
          assertThrows(
                  CompletionException.class, () -> storage.list("", 1).toCompletableFuture().join())
              .getCause());
      when(client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenThrow(new IllegalStateException("submit"));
      assertThrows(
          CompletionException.class, () -> storage.list("", 1).toCompletableFuture().join());
    }
  }

  @Test
  void lateHeadersAfterCancellationReleaseTheBodyEvenWhenNativeCancellationLosesARace() {
    S3AsyncClient client = mock(S3AsyncClient.class);
    CompletableFuture<ResponsePublisher<GetObjectResponse>> headers =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    when(client.getObject(
            any(GetObjectRequest.class),
            ArgumentMatchers
                .<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>
                    any()))
        .thenReturn(headers);
    AtomicInteger cancelled = new AtomicInteger();
    try (var signer = presigner()) {
      var storage = new S3AsyncObjectStorage(client, signer, "files", null, Duration.ofMinutes(1));
      var result = storage.get("x").toCompletableFuture();
      assertTrue(result.cancel(true));
      headers.complete(
          new ResponsePublisher<>(
              GetObjectResponse.builder().build(),
              target ->
                  target.onSubscribe(
                      new Subscription() {
                        @Override
                        public void request(long n) {
                          fail("An abandoned body must never be read");
                        }

                        @Override
                        public void cancel() {
                          cancelled.incrementAndGet();
                        }
                      })));
      assertEquals(1, cancelled.get());
    }
  }

  static class Probe implements Subscriber<ByteBuffer> {
    final AtomicReference<Subscription> subscription = new AtomicReference<>();
    final AtomicReference<ByteBuffer> value = new AtomicReference<>();

    @Override
    public void onSubscribe(Subscription incoming) {
      subscription.set(incoming);
    }

    @Override
    public void onNext(ByteBuffer chunk) {
      value.set(chunk);
    }

    @Override
    public void onError(Throwable failure) {
      fail(failure);
    }

    @Override
    public void onComplete() {}
  }

  static class FlowProbe implements Flow.Subscriber<ByteBuffer> {
    final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    final AtomicReference<ByteBuffer> value = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicInteger completed = new AtomicInteger();

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
      subscription.set(incoming);
    }

    @Override
    public void onNext(ByteBuffer chunk) {
      value.set(chunk);
    }

    @Override
    public void onError(Throwable error) {
      failure.set(error);
    }

    @Override
    public void onComplete() {
      completed.incrementAndGet();
    }
  }
}
