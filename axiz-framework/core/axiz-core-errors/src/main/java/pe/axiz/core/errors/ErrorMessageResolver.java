package pe.axiz.core.errors;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resolves localized messages for error detail; implemented by the i18n capability. */
@FunctionalInterface
public interface ErrorMessageResolver {

  Optional<String> resolve(String key, Locale locale, Map<String, Object> parameters);

  /** Resolver that never finds a message; the title from the catalog is used instead. */
  static ErrorMessageResolver none() {
    return (key, locale, parameters) -> Optional.empty();
  }
}
