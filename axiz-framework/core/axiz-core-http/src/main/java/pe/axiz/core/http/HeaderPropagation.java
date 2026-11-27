package pe.axiz.core.http;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.StandardHeaders;

/**
 * Calcula las cabeceras reenviadas a una dependencia: siempre el id de correlación y
 * Accept-Language, más la lista de permitidas del cliente. Las credenciales nunca se propagan (el
 * relay de tokens es un modo de autenticación explícito).
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
   * Cabeceras a agregar a la petición saliente.
   *
   * @param inboundNames nombres de las cabeceras entrantes
   * @param lookup búsqueda de cabeceras entrantes
   * @return nombre de cabecera a valor
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
