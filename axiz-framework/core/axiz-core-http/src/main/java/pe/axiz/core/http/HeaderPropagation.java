package pe.axiz.core.http;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.StandardHeaders;

/**
 * Computes the headers forwarded to a dependency: correlation id and Accept-Language always, plus
 * the client's allow-list. Credentials are never propagated (token relay is an explicit auth mode).
 */
public final class HeaderPropagation {

  private static final Set<String> ALWAYS =
      Set.of(StandardHeaders.CORRELATION_ID.toLowerCase(Locale.ROOT), "accept-language");

  private final Set<String> allowList;

  public HeaderPropagation(Collection<String> allowList) {
    this.allowList =
        allowList.stream()
            .map(h -> h.toLowerCase(Locale.ROOT))
            .filter(h -> !StandardHeaders.isNeverLogged(h))
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Headers to add to the outbound request.
   *
   * @param inboundNames names of the inbound headers
   * @param lookup inbound header lookup
   * @return header name to value
   */
  public Map<String, String> outbound(
      Collection<String> inboundNames, Function<String, @Nullable String> lookup) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String name : inboundNames) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (ALWAYS.contains(lower) || allowList.contains(lower)) {
        String value = lookup.apply(name);
        if (value != null
            && !value.isBlank()
            && value.indexOf('\n') < 0
            && value.indexOf('\r') < 0) {
          out.put(name, value);
        }
      }
    }
    return out;
  }
}
