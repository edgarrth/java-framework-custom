package pe.axiz.core.storage;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** URL de subida y cabeceras a enviar además del host derivado de la URL. */
public record PresignedUpload(URI url, Map<String, List<String>> headers) {
  public PresignedUpload {
    Objects.requireNonNull(url);
    headers =
        headers.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The constructor snapshots the map and each header list into unmodifiable containers.")
  public Map<String, List<String>> headers() {
    return headers;
  }

  /**
   * Las URL firmadas y los valores de cabecera son credenciales y no deben aparecer en cadenas de
   * diagnóstico.
   */
  @Override
  public String toString() {
    return "PresignedUpload[url=<redacted>, headers=" + headers.keySet() + "]";
  }
}
