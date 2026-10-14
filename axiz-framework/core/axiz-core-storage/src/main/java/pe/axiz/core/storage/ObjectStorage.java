package pe.axiz.core.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Object storage port (Amazon S3 in production). Content is always streamed. */
public interface ObjectStorage {

  void put(String key, InputStream content, ObjectMetadata metadata);

  Optional<StoredObject> get(String key);

  Optional<ObjectMetadata> head(String key);

  void delete(String key);

  /** Keys under a prefix (bounded by {@code max}). */
  List<String> list(String prefix, int max);

  /** Time-limited download URL; never longer than the configured maximum. */
  URL presignGet(String key, Duration ttl);

  /** Time-limited upload URL bound to a content type. */
  URL presignPut(String key, String contentType, Duration ttl);
}
