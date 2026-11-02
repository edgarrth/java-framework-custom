package pe.axiz.spring.storage;

import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import pe.axiz.core.storage.AsyncObjectStorage;
import pe.axiz.core.storage.AsyncStoredObject;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Deferred Reactor facade over the native port; content consumption owns the response lifetime. */
public final class ReactiveStorage {
  private final AsyncObjectStorage storage;

  public ReactiveStorage(AsyncObjectStorage storage) {
    this.storage = java.util.Objects.requireNonNull(storage);
  }

  public Mono<Void> put(
      String key, org.reactivestreams.Publisher<ByteBuffer> content, ObjectMetadata metadata) {
    return ReactiveStages.from(
        () -> storage.put(key, JdkFlowAdapter.publisherToFlowPublisher(content), metadata));
  }

  /** The caller owns a present object's lifetime; prefer {@link #content(String)} for streaming. */
  public Mono<AsyncStoredObject> get(String key) {
    return ReactiveStages.from(() -> storage.get(key))
        .flatMap(Mono::justOrEmpty)
        .doOnDiscard(Object.class, ReactiveStorage::discard);
  }

  /** Releases or aborts the response on completion, failure, or downstream cancellation. */
  public Flux<ByteBuffer> content(String key) {
    return Flux.usingWhen(
        get(key),
        object -> ReactiveStages.flux(() -> JdkFlowAdapter.flowPublisherToFlux(object.content())),
        object -> Mono.fromRunnable(object::close));
  }

  public Mono<ObjectMetadata> head(String key) {
    return ReactiveStages.from(() -> storage.head(key)).flatMap(Mono::justOrEmpty);
  }

  public Mono<Void> delete(String key) {
    return ReactiveStages.from(() -> storage.delete(key));
  }

  public Mono<List<String>> list(String prefix, int max) {
    return ReactiveStages.from(() -> storage.list(prefix, max));
  }

  public Mono<URL> presignGet(String key, Duration ttl) {
    return ReactiveStages.from(() -> storage.presignGet(key, ttl));
  }

  public Mono<URL> presignPut(String key, String contentType, Duration ttl) {
    return ReactiveStages.from(() -> storage.presignPut(key, contentType, ttl));
  }

  public Mono<pe.axiz.core.storage.PresignedUpload> presignUpload(
      String key, String contentType, Duration ttl) {
    return ReactiveStages.from(() -> storage.presignUpload(key, contentType, ttl));
  }

  private static void discard(Object value) {
    if (value instanceof AsyncStoredObject object) object.close();
    else if (value instanceof Optional<?> optional) optional.ifPresent(ReactiveStorage::discard);
  }
}
