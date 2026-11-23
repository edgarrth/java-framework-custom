package pe.axiz.core.idempotency;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Respuesta guardada para replay. Inmutable: el cuerpo se copia a la entrada y a la salida, y dos
 * respuestas son iguales cuando el estado, las cabeceras y los bytes del cuerpo son iguales.
 */
public final class StoredResponse {

  /** Cabecera presente en los replays cuyo cuerpo era demasiado grande para almacenarse. */
  public static final String BODY_OMITTED_HEADER = "Idempotent-Body-Omitted";

  private final int status;
  private final Map<String, String> headers;
  private final byte[] body;

  /**
   * Crea una respuesta almacenada.
   *
   * @param status estado HTTP
   * @param headers cabeceras de la respuesta que vale la pena reproducir (content type, location)
   * @param body cuerpo de la respuesta
   */
  public StoredResponse(int status, Map<String, String> headers, byte[] body) {
    this.status = status;
    this.headers = Map.copyOf(headers);
    this.body = body.clone();
  }

  /**
   * Respuesta cuyo cuerpo superó el límite de almacenamiento: se conservan el estado y las
   * cabeceras (por ejemplo {@code Location}) para que un reintento conozca el resultado, pero no el
   * cuerpo.
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

  /** Copia del cuerpo. */
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
        + ", headerCount="
        + headers.size()
        + ", "
        + body.length
        + " bytes]";
  }
}
