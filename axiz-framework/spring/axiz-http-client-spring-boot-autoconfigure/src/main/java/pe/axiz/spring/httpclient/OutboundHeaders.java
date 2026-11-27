package pe.axiz.spring.httpclient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.http.HeaderPropagation;

/**
 * Cabeceras reenviadas a una dependencia desde la solicitud actual: id de correlación, idioma y las
 * cabeceras del catálogo en la lista permitida del cliente. El contexto de traza lo propaga la
 * instrumentación de observación, nunca se hace a mano.
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
    String tenant = m.tenantId();
    if (tenantHeader != null && tenant != null) {
      out.put(tenantHeader, tenant.toLowerCase(Locale.ROOT));
    }
    return out;
  }
}
