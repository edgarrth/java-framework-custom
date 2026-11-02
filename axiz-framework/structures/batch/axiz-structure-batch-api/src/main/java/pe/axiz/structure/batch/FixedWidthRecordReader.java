package pe.axiz.structure.batch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.fileformats.FixedWidthLayout;

/**
 * Fixed-width records (legacy files) parsed with a layout; header and trailer lines are recognized
 * by predicates and skipped (the trailer is kept for control totals).
 *
 * @param <T> record type
 */
public final class FixedWidthRecordReader<T> implements RecordReader<T> {

  private final BufferedReader reader;
  private final FixedWidthLayout layout;
  private final Function<Map<String, @Nullable Object>, T> mapper;
  private final Predicate<String> skip;
  private int line;
  private @Nullable String trailer;

  public FixedWidthRecordReader(
      InputStream in,
      Charset charset,
      FixedWidthLayout layout,
      Function<Map<String, @Nullable Object>, T> mapper,
      Predicate<String> headerOrTrailer) {
    this.reader = new BufferedReader(new InputStreamReader(in, charset));
    this.layout = Objects.requireNonNull(layout);
    this.mapper = Objects.requireNonNull(mapper);
    this.skip = Objects.requireNonNull(headerOrTrailer);
  }

  @Override
  public @Nullable T read() {
    try {
      String text;
      while ((text = reader.readLine()) != null) {
        line++;
        if (text.isBlank()) {
          continue;
        }
        if (skip.test(text)) {
          trailer = text;
          continue;
        }
        return mapper.apply(layout.parse(text, line));
      }
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Last header or trailer line seen (the trailer once the file is fully read). */
  public @Nullable String trailer() {
    return trailer;
  }

  @Override
  public int line() {
    return line;
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
