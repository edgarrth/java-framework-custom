package pe.axiz.framework.batch;

import java.io.Closeable;
import org.jspecify.annotations.Nullable;

/**
 * Sequential reader of file records with line tracking, so jobs can restart from the last committed
 * record and errors point to the offending line.
 *
 * @param <T> record type
 */
public interface RecordReader<T> extends Closeable {

  /** Next record or {@code null} at the end. */
  @Nullable T read();

  /** Physical line of the last record read (1-based). */
  int line();
}
