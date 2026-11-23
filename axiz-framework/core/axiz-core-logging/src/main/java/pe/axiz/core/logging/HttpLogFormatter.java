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
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.sensitive.JsonTextMasker;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;

/**
 * Formatea los logs de petición y respuesta HTTP: solo se escriben las cabeceras permitidas (nunca
 * Authorization ni Cookie), los cuerpos JSON se enmascaran por clave y luego se truncan; un cuerpo
 * que no puede enmascararse de forma segura no se escribe.
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

  /** Cabeceras permitidas con valores enmascarados. */
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
   * Lo que se escribe para un cuerpo demasiado grande para enmascararse: solo su tipo y tamaño.
   *
   * @param contentType content type del mensaje
   * @param bytes tamaño del cuerpo
   * @return el resumen
   */
  public String omitted(@Nullable String contentType, long bytes) {
    return "<"
        + (contentType == null ? "unknown" : contentType)
        + ", "
        + bytes
        + " bytes: too large to mask, not logged>";
  }

  /**
   * Cuerpo enmascarado y truncado de un mensaje leído completo; los cuerpos que no son JSON se
   * resumen por tipo y tamaño. Ver {@link #body(String, String, boolean)}.
   */
  public String body(@Nullable String contentType, @Nullable String body) {
    return body(contentType, body, true);
  }

  /**
   * Cuerpo enmascarado y truncado. Primero se enmascara el JSON y después se corta, de modo que un
   * corte nunca deja en claro parte de un valor sensible. Un cuerpo que no se leyó completo ({@code
   * complete} false, por ejemplo porque es mayor que lo que conserva el filtro) o que no es un
   * único valor JSON válido no se escribe: solo su tipo, su tamaño y el motivo.
   *
   * @param contentType content type del mensaje
   * @param body el cuerpo, o la parte que se conservó
   * @param complete indica si {@code body} es el cuerpo completo
   * @return lo que puede escribirse en el log
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
