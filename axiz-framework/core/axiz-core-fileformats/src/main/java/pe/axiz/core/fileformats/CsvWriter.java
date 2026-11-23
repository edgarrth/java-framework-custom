package pe.axiz.core.fileformats;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Escritor CSV RFC 4180. Con {@code protectFormulas}, a los valores que empiezan con {@code = +
 * - @} se les antepone una comilla para que las hojas de cálculo no los ejecuten (inyección CSV).
 */
public final class CsvWriter implements AutoCloseable {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Writer is an injected collaborator whose identity and lifecycle must be shared, not "
              + "copied.")
  private final Writer writer;

  private final char delimiter;
  private final boolean protectFormulas;

  public CsvWriter(Writer writer, char delimiter, boolean protectFormulas) {
    this.writer = writer;
    this.delimiter = delimiter;
    this.protectFormulas = protectFormulas;
  }

  public void write(List<@Nullable String> fields) {
    try {
      for (int i = 0; i < fields.size(); i++) {
        if (i > 0) {
          writer.write(delimiter);
        }
        writer.write(escape(fields.get(i)));
      }
      writer.write("\r\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  String escape(@Nullable String value) {
    if (value == null) {
      return "";
    }
    String v = value;
    if (protectFormulas && !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
      v = "'" + v;
    }
    boolean needsQuotes =
        v.indexOf(delimiter) >= 0
            || v.indexOf('"') >= 0
            || v.indexOf('\n') >= 0
            || v.indexOf('\r') >= 0;
    return needsQuotes ? '"' + v.replace("\"", "\"\"") + '"' : v;
  }

  @Override
  public void close() throws IOException {
    writer.close();
  }
}
