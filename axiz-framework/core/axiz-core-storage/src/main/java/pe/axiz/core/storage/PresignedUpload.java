package pe.axiz.core.storage;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Upload URL and headers to send in addition to the host derived from the URL. */
public record PresignedUpload(URI url, Map<String, List<String>> headers) {
  public PresignedUpload {
    Objects.requireNonNull(url);
    headers =
        headers.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  /** Signed URLs and header values are credentials and must not appear in diagnostic strings. */
  @Override
  public String toString() {
    return "PresignedUpload[url=<redacted>, headers=" + headers.keySet() + "]";
  }
}
