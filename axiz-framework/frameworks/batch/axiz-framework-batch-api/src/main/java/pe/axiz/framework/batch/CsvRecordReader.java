package pe.axiz.framework.batch;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.fileformats.CsvReader;
import pe.axiz.core.fileformats.FileFormatException;

/**
 * CSV records mapped to objects; the header can be skipped and blank lines are ignored.
 *
 * @param <T> record type
 */
public final class CsvRecordReader<T> implements RecordReader<T> {

  private final CsvReader csv;
  private final Function<List<String>, T> mapper;

  public CsvRecordReader(
      InputStream in,
      Charset charset,
      char delimiter,
      boolean header,
      Function<List<String>, T> mapper) {
    this.csv = new CsvReader(new InputStreamReader(in, charset), delimiter);
    this.mapper = Objects.requireNonNull(mapper);
    if (header) {
      csv.next();
    }
  }

  @Override
  public @Nullable T read() {
    List<String> row;
    do {
      row = csv.next();
    } while (row != null && row.size() == 1 && row.get(0).isBlank());
    if (row == null) {
      return null;
    }
    try {
      return mapper.apply(row);
    } catch (RuntimeException e) {
      throw new FileFormatException("Invalid record: " + e.getMessage(), csv.lineNumber());
    }
  }

  @Override
  public int line() {
    return csv.lineNumber();
  }

  @Override
  public void close() throws IOException {
    csv.close();
  }
}
