package pe.axiz.core.lang;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Pequeñas utilidades de cadenas que le faltan al JDK. */
public final class Strings {

  private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
  private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
  private static final Pattern EDGE_DASH = Pattern.compile("(^-+|-+$)");

  private Strings() {}

  public static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }

  public static boolean hasText(@Nullable String value) {
    return !isBlank(value);
  }

  /**
   * Indica si cada surrogate UTF-16 tiene pareja, de modo que la codificación UTF-8 no pierde
   * información de identidad.
   */
  public static boolean isWellFormedUnicode(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
          return false;
        }
      } else if (Character.isLowSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  public static @Nullable String trimToNull(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public static String truncate(String value, int maxLength) {
    Checks.notNegative(maxLength, "maxLength");
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  /** Elimina acentos, por ejemplo "Peñarol Ñandú" se convierte en "Penarol Nandu". */
  public static String stripAccents(String value) {
    return DIACRITICS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
  }

  /**
   * Identificador ASCII en minúsculas y kebab-case: "Pago Interbancario" se convierte en
   * "pago-interbancario".
   */
  public static String slug(String value) {
    String ascii = stripAccents(value).toLowerCase(Locale.ROOT);
    return EDGE_DASH.matcher(NON_SLUG.matcher(ascii).replaceAll("-")).replaceAll("");
  }

  /** Rellena a la izquierda con el carácter indicado hasta la longitud (nunca trunca). */
  public static String leftPad(String value, int length, char pad) {
    if (value.length() >= length) {
      return value;
    }
    return String.valueOf(pad).repeat(length - value.length()) + value;
  }

  /** Rellena a la derecha con el carácter indicado hasta la longitud (nunca trunca). */
  public static String rightPad(String value, int length, char pad) {
    if (value.length() >= length) {
      return value;
    }
    return value + String.valueOf(pad).repeat(length - value.length());
  }
}
