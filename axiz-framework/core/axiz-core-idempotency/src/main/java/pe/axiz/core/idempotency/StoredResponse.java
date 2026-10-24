package pe.axiz.core.idempotency;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Response saved for replay. Immutable: the body is copied on the way in and out, and two responses
 * are equal when status, headers and body bytes are equal.
 */
public final class StoredResponse {

  /** Header present on replays whose body was too large to be stored. */
  public static final String BODY_OMITTED_HEADER = "Idempotent-Body-Omitted";

  private final int status;
  private final Map<String, String> headers;
  private final byte[] body;

  /**
   * Creates a stored response.
   *
   * @param status HTTP status
   * @param headers response headers worth replaying (content type, location)
   * @param body response body
   */
  public StoredResponse(int status, Map<String, String> headers, byte[] body) {
    this.status = status;
    this.headers = Map.copyOf(headers);
    this.body = body.clone();
  }

  /**
   * Response whose body exceeded the storage limit: the status and headers (for example {@code
   * Location}) are kept so a retry learns the outcome, the body is not.
   */
  public static StoredResponse withoutBody(int status, Map<String, String> headers) {
    Map<String, String> h = new LinkedHashMap<>(headers);
    h.remove("Content-Type");
    h.put(BODY_OMITTED_HEADER, "true");
    return new StoredResponse(status, h, new byte[0]);
  }

  public int status() {
    return status;
  }

  public Map<String, String> headers() {
    return headers;
  }

  /** A copy of the body. */
  public byte[] body() {
    return body.clone();
  }

  public boolean bodyOmitted() {
    return "true".equals(headers.get(BODY_OMITTED_HEADER));
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof StoredResponse r
        && status == r.status
        && headers.equals(r.headers)
        && Arrays.equals(body, r.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, headers, Arrays.hashCode(body));
  }

  @Override
  public String toString() {
    return "StoredResponse[status="
        + status
        + ", headers="
        + headers
        + ", "
        + body.length
        + " bytes]";
  }
}
