package pe.axiz.core.commons;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Small string helpers that the JDK lacks. */
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

  /** Whether every UTF-16 surrogate is paired, so UTF-8 encoding loses no identity information. */
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

  /** Removes accents, e.g. "Peñarol Ñandú" becomes "Penarol Nandu". */
  public static String stripAccents(String value) {
    return DIACRITICS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
  }

  /** Lowercase ASCII kebab-case identifier: "Pago Interbancario" becomes "pago-interbancario". */
  public static String slug(String value) {
    String ascii = stripAccents(value).toLowerCase(Locale.ROOT);
    return EDGE_DASH.matcher(NON_SLUG.matcher(ascii).replaceAll("-")).replaceAll("");
  }

  /** Left-pads with the given character up to the length (never truncates). */
  public static String leftPad(String value, int length, char pad) {
    if (value.length() >= length) {
      return value;
    }
    return String.valueOf(pad).repeat(length - value.length()) + value;
  }

  /** Right-pads with the given character up to the length (never truncates). */
  public static String rightPad(String value, int length, char pad) {
    if (value.length() >= length) {
      return value;
    }
    return value + String.valueOf(pad).repeat(length - value.length());
  }
}
