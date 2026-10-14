package pe.axiz.core.logging;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.sensitive.JsonTextMasker;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;

/**
 * Formats HTTP request/response logs: only allow-listed headers are written (Authorization and
 * Cookie never), JSON bodies are masked by key and truncated.
 */
public final class HttpLogFormatter {

  private final Set<String> headerAllowList;
  private final int maxBodyLength;
  private final JsonTextMasker jsonMasker;
  private final ObjectMasker masker;

  public HttpLogFormatter(
      Collection<String> headerAllowList, int maxBodyLength, ObjectMasker masker) {
    this.headerAllowList =
        headerAllowList.stream()
            .map(h -> h.toLowerCase(Locale.ROOT))
            .filter(h -> !StandardHeaders.isNeverLogged(h))
            .collect(Collectors.toUnmodifiableSet());
    this.maxBodyLength = maxBodyLength;
    this.masker = masker;
    this.jsonMasker = new JsonTextMasker(masker);
  }

  /** Allow-listed headers with masked values. */
  public Map<String, String> headers(
      Collection<String> names, Function<String, @Nullable String> lookup) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String name : names) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (!headerAllowList.contains(lower)) {
        continue;
      }
      String value = masker.maskByKey(name, lookup.apply(name), OutputChannel.LOG);
      if (value != null) {
        out.put(name, value);
      }
    }
    return out;
  }

  /** Masked and truncated body; non-JSON bodies are summarized by type and size. */
  public String body(@Nullable String contentType, @Nullable String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) {
      return "<"
          + (contentType == null ? "unknown" : contentType)
          + ", "
          + body.length()
          + " chars>";
    }
    String masked = jsonMasker.mask(body, OutputChannel.LOG);
    return masked.length() > maxBodyLength
        ? masked.substring(0, maxBodyLength) + "...(truncated)"
        : masked;
  }
}
