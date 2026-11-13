package pe.axiz.spring.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces declared and chunked body limits before request deserialization. */
public final class RequestBodyLimitFilter extends OncePerRequestFilter implements Ordered {
  private final RequestBodyLimits limits;

  RequestBodyLimitFilter(RequestBodyLimits limits) {
    this.limits = limits;
  }

  @Override
  public int getOrder() {
    return RequestMetadataFilter.ORDER + 1;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long limit =
        limits.forPath(request.getRequestURI().substring(request.getContextPath().length()));
    if (request.getContentLengthLong() > limit) {
      reject(response);
      return;
    }
    CountingRequest counted = new CountingRequest(request, limit);
    try {
      chain.doFilter(counted, response);
    } catch (IOException | ServletException failure) {
      if (!counted.exceeded && !RequestBodyLimits.exceeded(failure)) throw failure;
      if (response.isCommitted()) throw failure;
      reject(response);
      return;
    }
    if (counted.exceeded && !response.isCommitted()) reject(response);
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.resetBuffer();
    response.setStatus(413);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(RequestBodyLimits.problem());
  }

  private static final class CountingRequest extends HttpServletRequestWrapper {
    private final long limit;
    private @Nullable ServletInputStream stream;
    private volatile boolean exceeded;

    CountingRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Servlet must return the same stateful bounded stream; copying would bypass "
                + "cumulative request limits.")
    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream != null) return stream;
      ServletInputStream original = super.getInputStream();
      stream =
          new ServletInputStream() {
            private long count;

            private void count(int n) throws IOException {
              if (n > 0) count += n;
              if (count > limit) {
                exceeded = true;
                throw new RequestBodyLimits.BodyTooLarge();
              }
            }

            @Override
            public int read() throws IOException {
              int value = original.read();
              count(value < 0 ? 0 : 1);
              return value;
            }

            @Override
            public int read(byte[] data, int off, int len) throws IOException {
              int n = original.read(data, off, (int) Math.min(len, Math.max(1, limit - count + 1)));
              count(n);
              return n;
            }

            @Override
            public boolean isFinished() {
              return original.isFinished();
            }

            @Override
            public boolean isReady() {
              return original.isReady();
            }

            @Override
            public void setReadListener(ReadListener listener) {
              original.setReadListener(listener);
            }
          };
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      return new BufferedReader(
          new InputStreamReader(
              getInputStream(),
              encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding)));
    }
  }
}
