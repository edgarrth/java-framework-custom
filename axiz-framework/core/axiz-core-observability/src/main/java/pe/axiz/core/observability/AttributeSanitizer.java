package pe.axiz.core.observability;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Protege los atributos de span y los eventos estructurados frente a datos personales: las claves
 * denegadas se descartan y los valores que parecen números de tarjeta o direcciones de correo se
 * redactan.
 */
public final class AttributeSanitizer {

  private static final Pattern CARD = Pattern.compile("\\b\\d{13,19}\\b");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Set<String> DEFAULT_DENIED =
      Set.of(
          "password",
          "token",
          "authorization",
          "cookie",
          "pan",
          "cvv",
          "pin",
          "otp",
          "secret",
          "document",
          "email",
          "phone");

  private final Set<String> denied;

  public AttributeSanitizer(Set<String> extraDeniedKeys) {
    var all = new HashSet<>(DEFAULT_DENIED);
    extraDeniedKeys.forEach(k -> all.add(k.toLowerCase(Locale.ROOT)));
    this.denied = Set.copyOf(all);
  }

  public static AttributeSanitizer defaults() {
    return new AttributeSanitizer(Set.of());
  }

  public Map<String, String> sanitize(Map<String, String> attributes) {
    Map<String, String> out = new LinkedHashMap<>();
    attributes.forEach(
        (k, v) -> {
          if (!isDenied(k)) {
            out.put(k, sanitizeValue(v));
          }
        });
    return out;
  }

  public String sanitizeValue(String value) {
    String noCards = CARD.matcher(value).replaceAll("[redacted-number]");
    return EMAIL.matcher(noCards).replaceAll("[redacted-email]");
  }

  private boolean isDenied(String key) {
    String k = key.toLowerCase(Locale.ROOT);
    for (String d : denied) {
      if (k.equals(d) || k.endsWith("." + d) || k.endsWith("_" + d)) {
        return true;
      }
    }
    return false;
  }
}
