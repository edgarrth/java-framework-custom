package pe.axiz.core.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/** In-memory storage for tests and local runs. */
public final class InMemoryObjectStorage implements ObjectStorage {

  private final Map<String, Entry> objects = new ConcurrentSkipListMap<>();

  @Override
  public void put(String key, InputStream content, ObjectMetadata metadata) {
    ObjectKey.validate(key);
    try {
      byte[] bytes = content.readAllBytes();
      String checksum =
          Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
      objects.put(
          key,
          new Entry(
              bytes,
              new ObjectMetadata(
                  metadata.contentType(), bytes.length, checksum, metadata.userMetadata())));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Optional<StoredObject> get(String key) {
    Entry e = objects.get(ObjectKey.validate(key));
    return e == null
        ? Optional.empty()
        : Optional.of(new StoredObject(key, e.metadata, new ByteArrayInputStream(e.bytes)));
  }

  @Override
  public Optional<ObjectMetadata> head(String key) {
    Entry e = objects.get(ObjectKey.validate(key));
    return e == null ? Optional.empty() : Optional.of(e.metadata);
  }

  @Override
  public void delete(String key) {
    objects.remove(ObjectKey.validate(key));
  }

  @Override
  public List<String> list(String prefix, int max) {
    return objects.keySet().stream().filter(k -> k.startsWith(prefix)).limit(max).toList();
  }

  @Override
  public URL presignGet(String key, Duration ttl) {
    return url("get", key, ttl);
  }

  @Override
  public URL presignPut(String key, String contentType, Duration ttl) {
    return url("put", key, ttl);
  }

  private static URL url(String op, String key, Duration ttl) {
    try {
      return URI.create(
              "http://localhost/axiz-storage/"
                  + ObjectKey.validate(key)
                  + "?op="
                  + op
                  + "&ttl="
                  + ttl.toSeconds())
          .toURL();
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Internal holder; a class rather than a record because it holds an array. */
  private static final class Entry {
    private final byte[] bytes;
    private final ObjectMetadata metadata;

    Entry(byte[] bytes, ObjectMetadata metadata) {
      this.bytes = bytes;
      this.metadata = metadata;
    }
  }
}
