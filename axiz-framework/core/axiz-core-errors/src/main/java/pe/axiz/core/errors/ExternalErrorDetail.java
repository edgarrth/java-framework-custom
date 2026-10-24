package pe.axiz.core.errors;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * What a dependency answered when it failed. Kept for logs, traces and (optionally) responses; an
 * empty body from the dependency is preserved as such instead of being lost.
 *
 * @param system logical name of the dependency (client name)
 * @param status HTTP status or -1 for transport errors
 * @param code error code reported by the dependency, if any
 * @param message message reported by the dependency, if any
 * @param body raw body (truncated to {@link #MAX_BODY}), may be empty but never null
 */
public record ExternalErrorDetail(
    String system, int status, @Nullable String code, @Nullable String message, String body)
    implements Serializable {

  public static final int MAX_BODY = 2048;

  public ExternalErrorDetail {
    body = body == null ? "" : (body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body);
  }

  public boolean emptyBody() {
    return body.isEmpty();
  }

  public boolean transportError() {
    return status < 0;
  }
}
