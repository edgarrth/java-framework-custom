package pe.axiz.adapter.storage.s3;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.storage.AsyncObjectStorage;
import pe.axiz.core.storage.AsyncStoredObject;
import pe.axiz.core.storage.ObjectKey;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.PresignedUpload;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Native SDK asynchronous storage with bounded page demand and streamed object bodies. */
public final class S3AsyncObjectStorage implements AsyncObjectStorage {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The native S3 client is a borrowed, thread-safe collaborator; copying would lose "
              + "lifecycle and transport ownership. Its private field is never returned.")
  private final S3AsyncClient s3;

  private final S3Requests requests;

  /** Clients and presigner are borrowed; construct and warm their credentials during startup. */
  public S3AsyncObjectStorage(
      S3AsyncClient s3,
      S3Presigner presigner,
      String bucket,
      @Nullable String kmsKeyId,
      Duration maxPresignTtl) {
    this.s3 = Objects.requireNonNull(s3);
    this.requests = new S3Requests(presigner, bucket, kmsKeyId, maxPresignTtl);
  }

  @Override
  public CompletionStage<Void> put(
      String key, Flow.Publisher<ByteBuffer> content, ObjectMetadata metadata) {
    return Stages.call(
        () -> {
          var request = requests.upload(key, metadata);
          var upload = new S3Streams.Upload(content, metadata.contentLength());
          var result = Stages.ignore(Stages.call(() -> s3.putObject(request, upload)));
          Stages.observe(
              result,
              (ignored, failure) -> upload.close(),
              failure -> result.toCompletableFuture().completeExceptionally(failure));
          return result;
        });
  }

  @Override
  public CompletionStage<Optional<AsyncStoredObject>> get(String key) {
    return Stages.call(
        () -> {
          var nativeCall =
              s3.getObject(
                  GetObjectRequest.builder()
                      .bucket(requests.bucket)
                      .key(ObjectKey.validate(key))
                      .build(),
                  AsyncResponseTransformer.toPublisher());
          CompletableFuture<Optional<AsyncStoredObject>> result =
              new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean interrupt) {
                  boolean cancelled = super.cancel(interrupt);
                  if (cancelled) nativeCall.cancel(interrupt);
                  return cancelled;
                }
              };
          Stages.observe(
              nativeCall,
              (response, failure) -> {
                if (failure != null) {
                  Throwable cause = Stages.unwrap(failure);
                  if (S3Requests.missing(cause)) result.complete(Optional.empty());
                  else result.completeExceptionally(cause);
                } else {
                  var headers = response.response();
                  var body = new S3Streams.Download(response);
                  try {
                    var object =
                        new AsyncStoredObject(
                            key,
                            S3Requests.metadata(
                                headers.contentType(),
                                headers.contentLength(),
                                headers.checksumSHA256(),
                                headers.metadata()),
                            body,
                            body);
                    if (!result.complete(Optional.of(object))) object.close();
                  } catch (RuntimeException | Error invalid) {
                    body.run();
                    result.completeExceptionally(invalid);
                  }
                }
              },
              result::completeExceptionally);
          return result;
        });
  }

  @Override
  public CompletionStage<Optional<ObjectMetadata>> head(String key) {
    return Stages.recover(
        Stages.map(
            Stages.call(
                () ->
                    s3.headObject(
                        HeadObjectRequest.builder()
                            .bucket(requests.bucket)
                            .key(ObjectKey.validate(key))
                            .build())),
            response ->
                Optional.of(
                    S3Requests.metadata(
                        response.contentType(),
                        response.contentLength(),
                        response.checksumSHA256(),
                        response.metadata()))),
        failure -> {
          if (S3Requests.missing(failure)) return Optional.empty();
          throw new CompletionException(failure);
        });
  }

  @Override
  public CompletionStage<Void> delete(String key) {
    return Stages.ignore(
        Stages.call(
            () ->
                s3.deleteObject(
                    DeleteObjectRequest.builder()
                        .bucket(requests.bucket)
                        .key(ObjectKey.validate(key))
                        .build())));
  }

  @Override
  public CompletionStage<List<String>> list(String prefix, int max) {
    return Stages.call(
        () -> {
          S3Requests.validateList(prefix, max);
          if (max == 0) return CompletableFuture.completedFuture(List.of());
          var listing = new Listing(prefix, max);
          listing.drain();
          return listing;
        });
  }

  /** Trampolines already-completed pages and owns the exact cancellable SDK future. */
  private final class Listing extends CompletableFuture<List<String>> {
    private final String prefix;
    private final int maximum;
    private final List<String> keys = new ArrayList<>();
    private final AtomicReference<@Nullable CompletableFuture<ListObjectsV2Response>> active =
        new AtomicReference<>();
    private final AtomicReference<@Nullable ListObjectsV2Response> received =
        new AtomicReference<>();
    private final AtomicInteger work = new AtomicInteger();
    private boolean first = true;
    private @Nullable String continuation;

    private Listing(String prefix, int maximum) {
      this.prefix = prefix;
      this.maximum = maximum;
    }

    @Override
    public boolean cancel(boolean interrupt) {
      boolean cancelled = super.cancel(interrupt);
      var pending = active.get();
      if (cancelled && pending != null) pending.cancel(interrupt);
      return cancelled;
    }

    private void drain() {
      if (work.getAndIncrement() != 0) return;
      int missed = 1;
      do {
        ListObjectsV2Response page = received.getAndSet(null);
        if (!isDone() && (page != null || first)) {
          first = false;
          if (page != null) {
            for (var entry : page.contents()) {
              if (keys.size() == maximum) break;
              keys.add(entry.key());
            }
            if (keys.size() == maximum || !Boolean.TRUE.equals(page.isTruncated())) {
              complete(List.copyOf(keys));
            } else {
              String next = page.nextContinuationToken();
              if (next == null || next.isBlank() || next.equals(continuation))
                completeExceptionally(
                    new IllegalStateException("S3 returned an invalid continuation token"));
              else continuation = next;
            }
          }
          if (!isDone()) {
            try {
              var call =
                  s3.listObjectsV2(
                      ListObjectsV2Request.builder()
                          .bucket(requests.bucket)
                          .prefix(prefix)
                          .maxKeys(Math.min(maximum - keys.size(), 1000))
                          .continuationToken(continuation)
                          .build());
              active.set(call);
              if (isCancelled()) call.cancel(true);
              Stages.observe(
                  call,
                  (response, failure) -> {
                    if (failure != null) completeExceptionally(Stages.unwrap(failure));
                    else {
                      received.set(response);
                      drain();
                    }
                  },
                  problem -> {
                    if (completeExceptionally(problem)) call.cancel(true);
                  });
            } catch (RuntimeException | Error failure) {
              completeExceptionally(failure);
            }
          }
        }
        missed = work.addAndGet(-missed);
      } while (missed != 0);
    }
  }

  @Override
  public CompletionStage<URL> presignGet(String key, Duration ttl) {
    return Stages.call(() -> CompletableFuture.completedFuture(requests.presignGet(key, ttl)));
  }

  @Override
  public CompletionStage<PresignedUpload> presignUpload(
      String key, String contentType, Duration ttl) {
    return Stages.call(
        () -> CompletableFuture.completedFuture(requests.presignUpload(key, contentType, ttl)));
  }
}
