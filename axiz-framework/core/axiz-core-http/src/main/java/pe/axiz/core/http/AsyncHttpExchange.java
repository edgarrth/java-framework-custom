package pe.axiz.core.http;

import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Bounded native HTTP exchange. Implementations must resolve DNS, connect and consume the body
 * without blocking, enforce the byte limit and request timeout, propagate cancellation and never
 * follow redirects. The application owns transport startup and shutdown.
 */
@FunctionalInterface
public interface AsyncHttpExchange {
  CompletionStage<Response> exchange(HttpRequest request, long maxResponseBytes);

  /** Token payloads remain accessible explicitly and are never included in diagnostics. */
  record Response(
      int status,
      @Nullable String contentType,
      @pe.axiz.core.sensitive.Sensitive(
              level = pe.axiz.core.sensitive.SensitivityLevel.CRITICAL,
              mask = pe.axiz.core.sensitive.MaskType.FULL)
          String body) {
    public Response {
      Objects.requireNonNull(body);
    }

    @Override
    public String toString() {
      return "Response[status=" + status + "]";
    }
  }
}
