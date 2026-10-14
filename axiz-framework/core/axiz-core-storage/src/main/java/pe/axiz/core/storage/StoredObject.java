package pe.axiz.core.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streamed object content; close it to release the connection.
 *
 * @param key object key
 * @param metadata metadata
 * @param content content stream
 */
public record StoredObject(String key, ObjectMetadata metadata, InputStream content)
    implements AutoCloseable {

  @Override
  public void close() throws IOException {
    content.close();
  }
}
