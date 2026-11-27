package pe.axiz.core.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de almacenamiento de objetos (Amazon S3 en producción). El contenido siempre se transmite
 * en streaming.
 */
public interface ObjectStorage {

  void put(String key, InputStream content, ObjectMetadata metadata);

  Optional<StoredObject> get(String key);

  Optional<ObjectMetadata> head(String key);

  void delete(String key);

  /** Claves bajo un prefijo (acotadas por {@code max}). */
  List<String> list(String prefix, int max);

  /** URL de descarga de tiempo limitado; nunca más larga que el máximo configurado. */
  URL presignGet(String key, Duration ttl);

  /**
   * Instrucciones de subida completas, incluidos el content type y las cabeceras firmadas
   * específicas del backend.
   */
  PresignedUpload presignUpload(String key, String contentType, Duration ttl);
}
