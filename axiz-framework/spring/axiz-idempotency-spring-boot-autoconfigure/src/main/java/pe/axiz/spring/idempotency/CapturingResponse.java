package pe.axiz.spring.idempotency;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * Respuesta que se transmite al cliente como de costumbre y conserva una copia acotada del cuerpo
 * para el almacén de idempotencia (a diferencia de un wrapper que cachea todo, la memoria nunca
 * supera el límite de almacenamiento).
 */
final class CapturingResponse extends HttpServletResponseWrapper {

  private final BoundedCapture capture;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Servlet contract returns the same response stream/writer; callers must write to the "
              + "captured body.")
  private @Nullable ServletOutputStream stream;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Servlet contract returns the same response stream/writer; callers must write to the "
              + "captured body.")
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

  /**
   * Vacía el writer (si se usa) para que la captura contenga todos los bytes escritos hasta el
   * momento.
   */
  void flushWriter() {
    if (writer != null) {
      writer.flush();
    }
  }
}
