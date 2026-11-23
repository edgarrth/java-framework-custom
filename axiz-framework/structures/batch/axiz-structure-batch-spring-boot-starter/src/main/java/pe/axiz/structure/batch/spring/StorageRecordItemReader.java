package pe.axiz.structure.batch.spring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.core.storage.StoredObject;
import pe.axiz.structure.batch.RecordReader;

/**
 * Lector reiniciable de Spring Batch sobre un archivo en almacenamiento de objetos (S3): la
 * cantidad de ítems se guarda en el contexto de ejecución del step, de modo que un job fallido se
 * reanuda después del último chunk confirmado.
 *
 * @param <T> tipo del registro
 */
public class StorageRecordItemReader<T> extends AbstractItemCountingItemStreamItemReader<T> {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObjectStorage is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final ObjectStorage storage;

  private final String key;
  private final Function<InputStream, RecordReader<T>> format;
  private @Nullable StoredObject object;
  private @Nullable RecordReader<T> reader;

  /**
   * Crea un lector cuyo estado guardado se almacena bajo {@code name} (los reinicios se reanudan a
   * partir de él).
   *
   * @param name nombre del lector (clave del estado guardado)
   * @param storage almacenamiento de objetos
   * @param key clave del objeto
   * @param format construye el lector de registros para el stream (CSV, ancho fijo, etc.)
   * @param <T> tipo del registro
   * @return el lector
   */
  public static <T> StorageRecordItemReader<T> named(
      String name,
      ObjectStorage storage,
      String key,
      Function<InputStream, RecordReader<T>> format) {
    StorageRecordItemReader<T> reader = new StorageRecordItemReader<>(storage, key, format);
    reader.setName(Objects.requireNonNull(name));
    return reader;
  }

  /**
   * Crea un lector sin nombre; es preferible {@link #named}, o llamar a {@code setName} antes de
   * que el step lo abra. El constructor sigue visible para los proxies con step scope.
   *
   * @param storage almacenamiento de objetos
   * @param key clave del objeto
   * @param format construye el lector de registros para el stream (CSV, ancho fijo, etc.)
   */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public StorageRecordItemReader(
      ObjectStorage storage, String key, Function<InputStream, RecordReader<T>> format) {
    this.storage = Objects.requireNonNull(storage);
    this.key = Objects.requireNonNull(key);
    this.format = Objects.requireNonNull(format);
  }

  @Override
  protected void doOpen() {
    StoredObject o =
        storage
            .get(key)
            .orElseThrow(() -> new IllegalStateException("Input file not found: " + key));
    this.object = o;
    this.reader = format.apply(o.content());
  }

  @Override
  protected @Nullable T doRead() {
    return Objects.requireNonNull(reader, "reader not opened").read();
  }

  @Override
  protected void doClose() throws Exception {
    if (reader != null) {
      reader.close();
    }
    if (object != null) {
      object.close();
    }
    reader = null;
    object = null;
  }
}
