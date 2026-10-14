package pe.axiz.core.idempotency;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response saved for replay.
 *
 * @param status HTTP status
 * @param headers response headers worth replaying (content type, location)
 * @param body response body
 */
public record StoredResponse(int status, Map<String, String> headers, byte[] body) {

  /** Header present on replays whose body was too large to be stored. */
  public static final String BODY_OMITTED_HEADER = "Idempotent-Body-Omitted";

  public StoredResponse {
    headers = Map.copyOf(headers);
    body = body.clone();
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

  public boolean bodyOmitted() {
    return "true".equals(headers.get(BODY_OMITTED_HEADER));
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}
