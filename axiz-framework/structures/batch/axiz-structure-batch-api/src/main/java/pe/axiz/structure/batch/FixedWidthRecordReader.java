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
 * Registros de ancho fijo (archivos de mainframe) parseados con una disposición; las líneas de
 * cabecera y trailer se reconocen mediante predicados y se omiten (el trailer se conserva para los
 * totales de control).
 *
 * @param <T> tipo del registro
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

  /**
   * Última línea de cabecera o trailer vista (el trailer una vez que el archivo se leyó por
   * completo).
   */
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
