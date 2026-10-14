package pe.axiz.core.fileformats;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.jspecify.annotations.Nullable;

/**
 * Streaming RFC 4180 CSV reader: quoted fields, escaped quotes ({@code ""}), delimiters and line
 * breaks inside quotes, CRLF or LF. Bounded field size protects against malformed files.
 */
public final class CsvReader implements Iterable<List<String>>, AutoCloseable {

  private static final int MAX_FIELD = 1_000_000;

  private final Reader reader;
  private final char delimiter;
  private int lineNumber;
  private int peeked = -2;

  public CsvReader(Reader reader, char delimiter) {
    this.reader = reader;
    this.delimiter = delimiter;
  }

  public int lineNumber() {
    return lineNumber;
  }

  /** Next record, or null at end of input. */
  public @Nullable List<String> next() {
    try {
      int c = read();
      if (c == -1) {
        return null;
      }
      List<String> fields = new ArrayList<>();
      StringBuilder field = new StringBuilder();
      boolean quoted = false;
      boolean fieldStarted = false;
      lineNumber++;
      while (true) {
        if (c == -1) {
          if (quoted) {
            throw new FileFormatException("Unterminated quoted field", lineNumber);
          }
          fields.add(field.toString());
          return fields;
        }
        char ch = (char) c;
        if (quoted) {
          if (ch == '"') {
            int n = read();
            if (n == '"') {
              field.append('"');
            } else {
              quoted = false;
              c = n;
              continue;
            }
          } else {
            if (ch == '\n') {
              lineNumber++;
            }
            field.append(ch);
          }
        } else if (ch == '"' && !fieldStarted) {
          quoted = true;
          fieldStarted = true;
        } else if (ch == delimiter) {
          fields.add(field.toString());
          field.setLength(0);
          fieldStarted = false;
        } else if (ch == '\r') {
          int n = read();
          if (n != '\n') {
            unread(n);
          }
          fields.add(field.toString());
          return fields;
        } else if (ch == '\n') {
          fields.add(field.toString());
          return fields;
        } else {
          field.append(ch);
          fieldStarted = true;
        }
        if (field.length() > MAX_FIELD) {
          throw new FileFormatException("Field too large", lineNumber);
        }
        c = read();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private int read() throws IOException {
    if (peeked != -2) {
      int p = peeked;
      peeked = -2;
      return p;
    }
    return reader.read();
  }

  private void unread(int c) {
    peeked = c;
  }

  @Override
  public Iterator<List<String>> iterator() {
    return new Iterator<>() {
      private @Nullable List<String> nextRecord = CsvReader.this.next();

      @Override
      public boolean hasNext() {
        return nextRecord != null;
      }

      @Override
      public List<String> next() {
        List<String> current = nextRecord;
        if (current == null) {
          throw new NoSuchElementException();
        }
        nextRecord = CsvReader.this.next();
        return current;
      }
    };
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
