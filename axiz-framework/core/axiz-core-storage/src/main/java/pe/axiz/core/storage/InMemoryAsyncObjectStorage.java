package pe.axiz.core.storage;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;

/** Native in-memory implementation for local/test use, with a bounded upload size per object. */
public final class InMemoryAsyncObjectStorage implements AsyncObjectStorage {
  private final ConcurrentSkipListMap<String, Entry> objects = new ConcurrentSkipListMap<>();
  private final int maximumObjectBytes;

  public InMemoryAsyncObjectStorage() {
    this(16 * 1024 * 1024);
  }

  public InMemoryAsyncObjectStorage(int maximumObjectBytes) {
    if (maximumObjectBytes < 1)
      throw new IllegalArgumentException("Maximum object size must be positive");
    this.maximumObjectBytes = maximumObjectBytes;
  }

  @Override
  public CompletionStage<Void> put(
      String key, Flow.Publisher<ByteBuffer> content, ObjectMetadata metadata) {
    return Stages.call(
        () -> {
          ObjectKey.validate(key);
          if (metadata.contentLength() < 0 || metadata.contentLength() > maximumObjectBytes)
            throw new IllegalArgumentException(
                "In-memory upload requires a known size within its configured bound");
          ByteBuffer bytes = ByteBuffer.allocate((int) metadata.contentLength());
          MessageDigest checksum;
          try {
            checksum = MessageDigest.getInstance("SHA-256");
          } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
          }
          AtomicReference<Flow.@Nullable Subscription> active = new AtomicReference<>();
          CompletableFuture<Void> result =
              new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean interrupt) {
                  boolean cancelled = super.cancel(interrupt);
                  Flow.Subscription subscription = active.get();
                  if (cancelled && subscription != null) subscription.cancel();
                  return cancelled;
                }
              };
          content.subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription incoming) {
                  if (!active.compareAndSet(null, incoming) || result.isDone()) incoming.cancel();
                  else incoming.request(1);
                }

                @Override
                public void onNext(ByteBuffer chunk) {
                  if (result.isDone()) return;
                  Flow.Subscription subscription = Objects.requireNonNull(active.get());
                  if (chunk.remaining() > bytes.remaining()) {
                    result.completeExceptionally(
                        new IllegalArgumentException("Upload exceeds declared content length"));
                    subscription.cancel();
                    return;
                  }
                  checksum.update(chunk.duplicate());
                  bytes.put(chunk.duplicate());
                  subscription.request(1);
                }

                @Override
                public void onError(Throwable failure) {
                  result.completeExceptionally(failure);
                }

                @Override
                public void onComplete() {
                  if (result.isDone()) return;
                  if (bytes.hasRemaining()) {
                    result.completeExceptionally(
                        new IllegalArgumentException(
                            "Upload is shorter than declared content length"));
                    return;
                  }
                  String actual = Base64.getEncoder().encodeToString(checksum.digest());
                  if (metadata.checksumSha256() != null
                      && !actual.equals(metadata.checksumSha256())) {
                    result.completeExceptionally(
                        new IllegalArgumentException("Upload checksum does not match"));
                    return;
                  }
                  objects.put(
                      key,
                      new Entry(
                          bytes.array(),
                          new ObjectMetadata(
                              metadata.contentType(),
                              bytes.capacity(),
                              actual,
                              metadata.userMetadata())));
                  result.complete(null);
                }
              });
          return result;
        });
  }

  @Override
  public CompletionStage<Optional<AsyncStoredObject>> get(String key) {
    return Stages.call(
        () -> {
          Entry entry = objects.get(ObjectKey.validate(key));
          if (entry == null) return CompletableFuture.completedFuture(Optional.empty());
          AtomicBoolean closed = new AtomicBoolean();
          AtomicBoolean subscribed = new AtomicBoolean();
          Flow.Publisher<ByteBuffer> publisher =
              target -> {
                boolean rejected = !subscribed.compareAndSet(false, true) || closed.get();
                target.onSubscribe(
                    new Flow.Subscription() {
                      private final AtomicBoolean delivered = new AtomicBoolean();

                      @Override
                      public void request(long n) {
                        if (rejected || closed.get() || !delivered.compareAndSet(false, true))
                          return;
                        if (n < 1) {
                          target.onError(new IllegalArgumentException("Demand must be positive"));
                          return;
                        }
                        target.onNext(ByteBuffer.wrap(entry.bytes).asReadOnlyBuffer());
                        if (!closed.get()) target.onComplete();
                      }

                      @Override
                      public void cancel() {
                        if (!rejected) closed.set(true);
                      }
                    });
                if (rejected)
                  target.onError(
                      new IllegalStateException("Download is closed or already subscribed"));
              };
          return CompletableFuture.completedFuture(
              Optional.of(
                  new AsyncStoredObject(key, entry.metadata, publisher, () -> closed.set(true))));
        });
  }

  @Override
  public CompletionStage<Optional<ObjectMetadata>> head(String key) {
    return Stages.call(
        () ->
            CompletableFuture.completedFuture(
                Optional.ofNullable(objects.get(ObjectKey.validate(key))).map(e -> e.metadata)));
  }

  @Override
  public CompletionStage<Void> delete(String key) {
    return Stages.call(
        () -> {
          objects.remove(ObjectKey.validate(key));
          return CompletableFuture.completedFuture(null);
        });
  }

  @Override
  public CompletionStage<List<String>> list(String prefix, int max) {
    return Stages.call(
        () -> {
          if (!prefix.isEmpty()) ObjectKey.validate(prefix);
          if (max < 0)
            throw new IllegalArgumentException("Maximum number of objects must not be negative");
          return CompletableFuture.completedFuture(
              objects.keySet().stream().filter(k -> k.startsWith(prefix)).limit(max).toList());
        });
  }

  @Override
  public CompletionStage<URL> presignGet(String key, Duration ttl) {
    return url("get", key, ttl);
  }

  @Override
  public CompletionStage<URL> presignPut(String key, String contentType, Duration ttl) {
    return url("put", key, ttl);
  }

  private static CompletionStage<URL> url(String operation, String key, Duration ttl) {
    return Stages.call(
        () -> {
          if (ttl.compareTo(Duration.ofSeconds(1)) < 0)
            throw new IllegalArgumentException("TTL must be at least one second");
          try {
            return CompletableFuture.completedFuture(
                URI.create(
                        "http://localhost/axiz-storage/"
                            + ObjectKey.validate(key)
                            + "?op="
                            + operation
                            + "&ttl="
                            + ttl.toSeconds())
                    .toURL());
          } catch (MalformedURLException failure) {
            throw new IllegalStateException(failure);
          }
        });
  }

  private static final class Entry {
    private final byte[] bytes;
    private final ObjectMetadata metadata;

    Entry(byte[] bytes, ObjectMetadata metadata) {
      this.bytes = bytes;
      this.metadata = metadata;
    }
  }
}
