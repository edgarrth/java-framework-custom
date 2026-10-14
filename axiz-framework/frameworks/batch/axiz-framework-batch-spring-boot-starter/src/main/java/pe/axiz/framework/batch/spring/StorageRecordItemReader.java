package pe.axiz.framework.batch.spring;

import java.io.InputStream;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.core.storage.StoredObject;
import pe.axiz.framework.batch.RecordReader;

/**
 * Restartable Spring Batch reader over a file in object storage (S3): the item count is saved in
 * the step execution context, so a failed job resumes after the last committed chunk.
 *
 * @param <T> record type
 */
public class StorageRecordItemReader<T> extends AbstractItemCountingItemStreamItemReader<T> {

  private final ObjectStorage storage;
  private final String key;
  private final Function<InputStream, RecordReader<T>> format;
  private @Nullable StoredObject object;
  private @Nullable RecordReader<T> reader;

  /**
   * Creates the reader.
   *
   * @param name reader name (key of the saved state)
   * @param storage object storage
   * @param key object key
   * @param format builds the record reader for the stream (CSV, fixed width...)
   */
  public StorageRecordItemReader(
      String name,
      ObjectStorage storage,
      String key,
      Function<InputStream, RecordReader<T>> format) {
    this.storage = Objects.requireNonNull(storage);
    this.key = Objects.requireNonNull(key);
    this.format = Objects.requireNonNull(format);
    setName(name);
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
