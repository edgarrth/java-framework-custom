package pe.axiz.spring.logging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Escribe de inmediato hacia la salida y conserva una copia acotada solo para diagnósticos JSON
 * completos.
 */
final class BoundedCaptureResponseWrapper extends HttpServletResponseWrapper {
  private final HttpServletRequest request;
  private final int limit;
  private final ByteArrayOutputStream captured;
  private @Nullable ServletOutputStream output;
  private @Nullable PrintWriter writer;
  private long bytes;
  private boolean incomplete;

  BoundedCaptureResponseWrapper(
      HttpServletRequest request, HttpServletResponse response, int limit) {
    super(response);
    if (limit < 1 || limit == Integer.MAX_VALUE)
      throw new IllegalArgumentException("Invalid capture limit");
    this.request = request;
    this.limit = limit;
    captured = new ByteArrayOutputStream(Math.min(limit + 1, 4096));
  }

  boolean eligible() {
    String contentType = getContentType();
    String disposition = getHeader("Content-Disposition");
    return !request.isAsyncStarted()
        && (disposition == null || !disposition.toLowerCase(Locale.ROOT).contains("attachment"))
        && contentType != null
        && contentType.toLowerCase(Locale.ROOT).contains("json")
        && !contentType.toLowerCase(Locale.ROOT).contains("ndjson");
  }

  boolean complete() {
    return !incomplete && bytes <= limit;
  }

  long size() {
    return bytes;
  }

  byte[] capturedBytes() {
    return captured.toByteArray();
  }

  void finish() {
    if (writer != null && !request.isAsyncStarted()) writer.flush();
  }

  private void capture(byte[] value, int offset, int length) {
    bytes += length;
    if (!eligible()) {
      incomplete = true;
      return;
    }
    int kept = Math.min(length, Math.max(0, limit + 1 - captured.size()));
    captured.write(value, offset, kept);
  }

  private ServletOutputStream stream() throws IOException {
    if (output == null) {
      var delegate = super.getOutputStream();
      output =
          new ServletOutputStream() {
            @Override
            public boolean isReady() {
              return delegate.isReady();
            }

            @Override
            public void setWriteListener(WriteListener listener) {
              delegate.setWriteListener(listener);
            }

            @Override
            public void write(int value) throws IOException {
              write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] value, int offset, int length) throws IOException {
              try {
                delegate.write(value, offset, length);
                capture(value, offset, length);
              } catch (IOException failure) {
                incomplete = true;
                throw failure;
              }
            }

            @Override
            public void flush() throws IOException {
              delegate.flush();
            }

            @Override
            public void close() throws IOException {
              delegate.close();
            }
          };
    }
    return output;
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (writer != null) throw new IllegalStateException("getWriter already called");
    return stream();
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Servlet requires the same writer instance for ordered response writes; a copy "
              + "would change bytes, encoding and flush semantics.")
  @Override
  public PrintWriter getWriter() throws IOException {
    if (writer == null) {
      if (output != null) throw new IllegalStateException("getOutputStream already called");
      var delegate = super.getWriter();
      writer =
          new PrintWriter(
              new Writer() {
                @Override
                public void write(char[] value, int offset, int length) {
                  delegate.write(value, offset, length);
                  int kept = Math.min(length, limit + 1);
                  boolean surrogate = false;
                  for (int i = offset; i < offset + kept; i++)
                    surrogate |= Character.isSurrogate(value[i]);
                  if (length > kept || surrogate) {
                    incomplete = true;
                    bytes += length;
                  } else {
                    byte[] encoded =
                        new String(value, offset, kept)
                            .getBytes(Charset.forName(getCharacterEncoding()));
                    capture(encoded, 0, encoded.length);
                  }
                }

                @Override
                public void flush() {
                  delegate.flush();
                }

                @Override
                public void close() {
                  delegate.close();
                }
              });
    }
    return writer;
  }

  @Override
  public void flushBuffer() throws IOException {
    finish();
    super.flushBuffer();
  }

  @Override
  public void resetBuffer() {
    super.resetBuffer();
    captured.reset();
    bytes = 0;
    incomplete = false;
  }

  @Override
  public void reset() {
    super.reset();
    captured.reset();
    bytes = 0;
    incomplete = false;
    output = null;
    writer = null;
  }
}
