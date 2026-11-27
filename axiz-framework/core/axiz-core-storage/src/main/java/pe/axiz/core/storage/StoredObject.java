package pe.axiz.core.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Contenido de un objeto en streaming; se cierra para liberar la conexión.
 *
 * @param key clave del objeto
 * @param metadata metadatos
 * @param content stream del contenido
 */
public record StoredObject(String key, ObjectMetadata metadata, InputStream content)
    implements AutoCloseable {

  @Override
  public void close() throws IOException {
    content.close();
  }
}
