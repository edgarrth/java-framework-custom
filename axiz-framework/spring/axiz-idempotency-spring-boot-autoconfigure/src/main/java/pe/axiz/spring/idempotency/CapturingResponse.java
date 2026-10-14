package pe.axiz.spring.idempotency;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Response that streams to the client as usual and keeps a bounded copy of the body for the
 * idempotency store (unlike a fully caching wrapper, memory never exceeds the storage limit).
 */
final class CapturingResponse extends HttpServletResponseWrapper {

  private final BoundedCapture capture;
  private @Nullable ServletOutputStream stream;
  private @Nullable PrintWriter writer;

  CapturingResponse(HttpServletResponse response, int limit) {
    super(response);
    this.capture = new BoundedCapture(limit);
  }

  BoundedCapture capture() {
    return capture;
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (stream == null) {
      ServletOutputStream target = super.getOutputStream();
      stream =
          new ServletOutputStream() {
            @Override
            public boolean isReady() {
              return target.isReady();
            }

            @Override
            public void setWriteListener(WriteListener listener) {
              target.setWriteListener(listener);
            }

            @Override
            public void write(int b) throws IOException {
              target.write(b);
              capture.write(new byte[] {(byte) b});
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
              target.write(b, off, len);
              capture.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
              target.flush();
            }
          };
    }
    return stream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (writer == null) {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), false);
    }
    return writer;
  }

  @Override
  public void flushBuffer() throws IOException {
    if (writer != null) {
      writer.flush();
    }
    super.flushBuffer();
  }

  /** Flushes the writer (if used) so the capture holds every byte written so far. */
  void flushWriter() {
    if (writer != null) {
      writer.flush();
    }
  }
}
