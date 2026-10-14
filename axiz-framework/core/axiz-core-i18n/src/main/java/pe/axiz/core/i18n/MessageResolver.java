package pe.axiz.core.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resolves localized messages with named parameters ({@code {name}}). */
public interface MessageResolver {

  Optional<String> find(String key, Locale locale, Map<String, ?> parameters);

  /** Returns the message or the key itself when it is not defined. */
  default String get(String key, Locale locale, Map<String, ?> parameters) {
    return find(key, locale, parameters).orElse(key);
  }

  default String get(String key, Locale locale) {
    return get(key, locale, Map.of());
  }
}
