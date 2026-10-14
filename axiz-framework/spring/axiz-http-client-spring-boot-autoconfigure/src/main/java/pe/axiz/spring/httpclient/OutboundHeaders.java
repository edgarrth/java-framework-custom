package pe.axiz.spring.httpclient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.http.HeaderPropagation;

/**
 * Headers forwarded to a dependency from the current request: correlation id, language and the
 * catalog headers in the client's allow-list. Trace context is propagated by the observation
 * instrumentation, never by hand.
 */
public final class OutboundHeaders {

  private OutboundHeaders() {}

  public static Map<String, String> of(
      @Nullable RequestMetadata m, ClientSettings settings, @Nullable String tenantHeader) {
    if (m == null) {
      return Map.of();
    }
    Map<String, String> inbound = new LinkedHashMap<>(m.attributes());
    inbound.put(StandardHeaders.CORRELATION_ID, m.correlationId());
    if (!m.locale().getLanguage().isEmpty()) {
      inbound.put(StandardHeaders.ACCEPT_LANGUAGE, m.locale().toLanguageTag());
    }
    Map<String, String> out =
        new LinkedHashMap<>(
            new HeaderPropagation(settings.propagateHeaders())
                .outbound(inbound.keySet(), inbound::get));
    if (tenantHeader != null && m.tenantId() != null) {
      out.put(tenantHeader, m.tenantId().toLowerCase(Locale.ROOT));
    }
    return out;
  }
}
