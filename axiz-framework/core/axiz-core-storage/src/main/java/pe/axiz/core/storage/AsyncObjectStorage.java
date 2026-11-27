package pe.axiz.core.storage;

import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Almacenamiento de objetos asíncrono nativo. El contenido se transmite en streaming con demanda y
 * cancelación; los publishers suministrados para las subidas deben soportar replay cuando el
 * backend reintenta un intento.
 */
public interface AsyncObjectStorage {
  CompletionStage<Void> put(
      String key, Flow.Publisher<ByteBuffer> content, ObjectMetadata metadata);

  /**
   * Se completa cuando llegan las cabeceras. Un objeto presente se consume o se cierra para liberar
   * su conexión.
   */
  CompletionStage<Optional<AsyncStoredObject>> get(String key);

  CompletionStage<Optional<ObjectMetadata>> head(String key);

  CompletionStage<Void> delete(String key);

  CompletionStage<List<String>> list(String prefix, int max);

  CompletionStage<URL> presignGet(String key, Duration ttl);

  /** Instrucciones de subida completas; la cancelación llega a la operación de firma nativa. */
  CompletionStage<PresignedUpload> presignUpload(String key, String contentType, Duration ttl);
}
