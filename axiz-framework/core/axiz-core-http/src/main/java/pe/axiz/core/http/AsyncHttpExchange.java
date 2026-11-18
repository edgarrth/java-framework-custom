package pe.axiz.core.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Bounded native HTTP exchange. Implementations must resolve DNS, connect and consume the body
 * without blocking, enforce the byte limit and request timeout, propagate cancellation and never
 * follow redirects. The application owns transport startup and shutdown.
 */
@FunctionalInterface
public interface AsyncHttpExchange {
  CompletionStage<Response> exchange(Request request, long maxResponseBytes);

  /** Immutable wire data; diagnostics never expose the URI, credentials or body. */
  record Request(
      URI uri,
      String method,
      @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL)
          Map<String, List<String>> headers,
      @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL) String body,
      Duration timeout) {
    public Request {
      Objects.requireNonNull(uri);
      Objects.requireNonNull(method);
      Objects.requireNonNull(body);
      Objects.requireNonNull(timeout);
      if (!timeout.isPositive())
        throw new IllegalArgumentException("HTTP timeout must be positive");
      var copy = new LinkedHashMap<String, List<String>>();
      headers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
      headers = Map.copyOf(copy);
    }

    @Override
    public String toString() {
      return "Request[redacted]";
    }
  }

  /** Token payloads remain accessible explicitly and are never included in diagnostics. */
  record Response(
      int status,
      @Nullable String contentType,
      @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL) String body) {
    public Response {
      Objects.requireNonNull(body);
    }

    @Override
    public String toString() {
      return "Response[status=" + status + "]";
    }
  }
}
