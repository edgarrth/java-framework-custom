package pe.axiz.core.id;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Ids de correlación y de petición: generación y validación de los valores entrantes. */
public final class CorrelationIds {

  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

  private CorrelationIds() {}

  public static String newId() {
    return UuidV7.generate().toString();
  }

  /**
   * Indica si un valor entrante es seguro de reutilizar (sin espacios, CR/LF ni caracteres de
   * inyección en logs).
   */
  public static boolean isValid(@Nullable String value) {
    return value != null && SAFE.matcher(value).matches();
  }

  /** Reutiliza el id entrante cuando es válido; en otro caso genera uno nuevo. */
  public static String reuseOrCreate(@Nullable String inbound) {
    // la verificación de null es explícita para que el análisis de nulos (NullAway) la vea
    return inbound != null && isValid(inbound) ? inbound : newId();
  }
}
