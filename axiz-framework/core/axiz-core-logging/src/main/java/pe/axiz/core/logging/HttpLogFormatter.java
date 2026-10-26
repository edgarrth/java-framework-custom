package pe.axiz.core.logging;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * Cookie never), JSON bodies are masked by key and then truncated; a body that cannot be masked
 * safely is not written.
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

  /**
   * What is written for a body too large to be masked: its type and size only.
   *
   * @param contentType content type of the message
   * @param bytes size of the body
   * @return the summary
   */
  public String omitted(@Nullable String contentType, long bytes) {
    return "<"
        + (contentType == null ? "unknown" : contentType)
        + ", "
        + bytes
        + " bytes: too large to mask, not logged>";
  }

  /**
   * Masked and truncated body of a message that was read whole; non-JSON bodies are summarized by
   * type and size. See {@link #body(String, String, boolean)}.
   */
  public String body(@Nullable String contentType, @Nullable String body) {
    return body(contentType, body, true);
  }

  /**
   * Masked and truncated body. The JSON is masked first and cut afterwards, so a cut never leaves
   * part of a sensitive value in clear. A body that was not read whole ({@code complete} false, for
   * example because it is larger than what the filter keeps) or that is not one valid JSON value is
   * not written: only its type, its size and the reason.
   *
   * @param contentType content type of the message
   * @param body the body, or the part of it that was kept
   * @param complete whether {@code body} is the whole body
   * @return what can be written to the log
   */
  public String body(@Nullable String contentType, @Nullable String body, boolean complete) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    String type = contentType == null ? "unknown" : contentType;
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) {
      return "<" + type + ", " + body.length() + " chars>";
    }
    if (!complete) {
      return "<" + type + ", more than " + body.length() + " chars: too large to mask, not logged>";
    }
    Optional<String> masked = jsonMasker.tryMask(body, OutputChannel.LOG);
    if (masked.isEmpty()) {
      return "<" + type + ", " + body.length() + " chars: not a complete JSON value, not logged>";
    }
    String text = masked.get();
    return text.length() > maxBodyLength
        ? text.substring(0, maxBodyLength) + "...(truncated)"
        : text;
  }
}
