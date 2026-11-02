package pe.axiz.core.storage;

import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Native asynchronous object storage. Content is streamed with demand and cancellation; publishers
 * supplied for uploads must support replay when the backend retries an attempt.
 */
public interface AsyncObjectStorage {
  CompletionStage<Void> put(
      String key, Flow.Publisher<ByteBuffer> content, ObjectMetadata metadata);

  /** Complete when headers arrive. Consume or close a present object to release its connection. */
  CompletionStage<Optional<AsyncStoredObject>> get(String key);

  CompletionStage<Optional<ObjectMetadata>> head(String key);

  CompletionStage<Void> delete(String key);

  CompletionStage<List<String>> list(String prefix, int max);

  CompletionStage<URL> presignGet(String key, Duration ttl);

  CompletionStage<URL> presignPut(String key, String contentType, Duration ttl);

  /** Complete upload instructions; cancellation reaches the native signing operation. */
  default CompletionStage<PresignedUpload> presignUpload(
      String key, String contentType, Duration ttl) {
    return pe.axiz.core.commons.Stages.map(
        pe.axiz.core.commons.Stages.call(() -> presignPut(key, contentType, ttl)),
        url ->
            new PresignedUpload(
                java.net.URI.create(url.toString()),
                java.util.Map.of("Content-Type", List.of(contentType))));
  }
}
