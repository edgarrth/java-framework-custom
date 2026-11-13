package pe.axiz.core.storage;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The constructor snapshots the map and each header list into unmodifiable containers.")
  public Map<String, List<String>> headers() {
    return headers;
  }

  /** Signed URLs and header values are credentials and must not appear in diagnostic strings. */
  @Override
  public String toString() {
    return "PresignedUpload[url=<redacted>, headers=" + headers.keySet() + "]";
  }
}
