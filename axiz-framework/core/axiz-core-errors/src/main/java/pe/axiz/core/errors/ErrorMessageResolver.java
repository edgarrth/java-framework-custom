package pe.axiz.core.errors;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resuelve mensajes localizados para el detalle del error; lo implementa la capacidad de i18n. */
@FunctionalInterface
public interface ErrorMessageResolver {

  Optional<String> resolve(String key, Locale locale, Map<String, Object> parameters);

  /** Resolver que nunca encuentra un mensaje; en su lugar se usa el título del catálogo. */
  static ErrorMessageResolver none() {
    return (key, locale, parameters) -> Optional.empty();
  }
}
