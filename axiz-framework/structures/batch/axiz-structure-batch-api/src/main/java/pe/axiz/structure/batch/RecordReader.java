package pe.axiz.structure.batch;

import java.io.Closeable;
import org.jspecify.annotations.Nullable;

/**
 * Lector secuencial de registros de archivo con seguimiento de líneas, para que los jobs puedan
 * reiniciar desde el último registro confirmado y los errores apunten a la línea problemática.
 *
 * @param <T> tipo del registro
 */
public interface RecordReader<T> extends Closeable {

  /** Siguiente registro o {@code null} al final. */
  @Nullable T read();

  /** Línea física del último registro leído (desde 1). */
  int line();
}
