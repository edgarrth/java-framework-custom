package pe.axiz.core.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resuelve mensajes localizados con parámetros con nombre ({@code {name}}). */
public interface MessageResolver {

  Optional<String> find(String key, Locale locale, Map<String, ?> parameters);

  /** Devuelve el mensaje, o la propia clave cuando no está definida. */
  default String get(String key, Locale locale, Map<String, ?> parameters) {
    return find(key, locale, parameters).orElse(key);
  }

  default String get(String key, Locale locale) {
    return get(key, locale, Map.of());
  }
}
