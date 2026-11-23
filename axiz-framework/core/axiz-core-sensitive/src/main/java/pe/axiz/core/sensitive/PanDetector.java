package pe.axiz.core.sensitive;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.identifiers.CheckDigits;

/**
 * Detección acotada de candidatos a PAN; las coincidencias Luhn son una heurística, no una
 * clasificación de datos.
 */
public final class PanDetector {

  public static final int MIN_DIGITS = 12;
  public static final int MAX_DIGITS = 19;

  private PanDetector() {}

  /** True para 12 a 19 dígitos que pasan Luhn. */
  public static boolean isPan(String digits) {
    return digits.length() >= MIN_DIGITS
        && digits.length() <= MAX_DIGITS
        && CheckDigits.allDigits(digits)
        && CheckDigits.luhn(digits);
  }

  /**
   * Un número entero que es un número de tarjeta se convierte en su texto truncado; cualquier otra
   * cosa se devuelve sin cambios. Solo se inspeccionan los tipos exactos del JDK: sin toString() de
   * clases Number desconocidas.
   */
  public static Object redactNumber(Number value) {
    String digits = null;
    if (value.getClass() == Long.class) {
      long v = value.longValue();
      digits = v > 0 ? Long.toString(v) : null;
    } else if (value.getClass() == BigInteger.class) {
      BigInteger v = (BigInteger) value;
      digits = v.signum() > 0 && v.bitLength() <= 64 ? v.toString() : null;
    } else if (value.getClass() == BigDecimal.class) {
      digits = wholeDigits((BigDecimal) value);
    } else if (value.getClass() == Double.class) {
      double v = value.doubleValue(); // los enteros menores que 2^53 son exactos en un double
      digits =
          v >= 1e11 && v < 9_007_199_254_740_992d && v == Math.rint(v)
              ? Long.toString((long) v)
              : null;
    }
    return digits != null && isPan(digits) ? truncate(digits) : value;
  }

  /**
   * Dígitos de un decimal positivo que es matemáticamente entero, sea cual sea su escala; en otro
   * caso null.
   */
  private static @Nullable String wholeDigits(BigDecimal v) {
    if (v.signum() <= 0 || v.precision() > 64) { // se acota el trabajo antes de normalizar
      return null;
    }
    long integerDigits = (long) v.precision() - v.scale(); // 4111111111111111.0 -> 17 - 1 = 16
    if (integerDigits < MIN_DIGITS || integerDigits > MAX_DIGITS) {
      return null;
    }
    BigDecimal stripped = v.stripTrailingZeros();
    return stripped.scale() <= 0 ? stripped.toBigIntegerExact().toString() : null;
  }

  private static String truncate(String digits) {
    return digits.substring(0, 6)
        + "*".repeat(digits.length() - 10)
        + digits.substring(digits.length() - 4);
  }

  /** Racha más larga de caracteres separadores aceptada entre dos grupos de dígitos. */
  private static final int MAX_SEPARATOR_RUN = 2;

  /**
   * Reemplaza cada racha de 12 a 19 dígitos que pasa Luhn. Los dígitos pueden ser cualquier dígito
   * decimal Unicode (de ancho completo, arábigo-índicos, etc.) y los grupos pueden unirse con hasta
   * dos caracteres separadores: espacios, tabulaciones, espacios no separables, guiones y rayas,
   * {@code .}, {@code /} o {@code _}. Los seis primeros y los cuatro últimos dígitos conservados se
   * escriben en ASCII.
   */
  public static String redact(String text) {
    StringBuilder out = new StringBuilder(text.length());
    return scan(text, out) ? out.toString() : text;
  }

  /** Indica si el escáner acotado de dígitos encuentra algún candidato similar a un PAN. */
  public static boolean containsPan(String text) {
    return scan(text, null);
  }

  /** Detecta los mismos candidatos que {@link #redact(String)} sin copiar la entrada. */
  public static boolean containsPan(char[] text) {
    return scan(CharBuffer.wrap(text), null);
  }

  /**
   * Recorre {@code text}; cuando {@code out} no es null recibe el texto redactado. Devuelve si se
   * encontró un candidato (con {@code out} null se detiene en el primero).
   */
  private static boolean scan(CharSequence text, @Nullable StringBuilder out) {
    int n = text.length();
    char[] digits = new char[MAX_DIGITS + 1];
    boolean found = false;
    int copied = 0; // text[0, copied) ya está en out
    try {
      int i = 0;
      while (i < n) {
        if (!digit(text.charAt(i)) || (i > 0 && digit(text.charAt(i - 1)))) {
          i++;
          continue;
        }
        // Candidato: este grupo de dígitos más los siguientes unidos por rachas cortas de
        // separadores.
        int count = 0;
        int matchEnd = -1; // fin exclusivo del PAN válido más largo
        int matchLength = 0;
        int j = i;
        while (j < n && count <= MAX_DIGITS) {
          char c = text.charAt(j);
          if (digit(c)) {
            digits[count++] = (char) ('0' + Character.digit(c, 10));
            j++;
            boolean groupEnds = j == n || !digit(text.charAt(j));
            if (groupEnds
                && count >= MIN_DIGITS
                && count <= MAX_DIGITS
                && CheckDigits.luhn(CharBuffer.wrap(digits, 0, count))) {
              matchEnd = j;
              matchLength = count;
            }
          } else {
            int run = separatorRun(text, j);
            if (run == 0 || j + run >= n || !digit(text.charAt(j + run))) break;
            j += run;
          }
        }
        if (matchEnd < 0) {
          i++; // se reintenta desde el siguiente grupo
          continue;
        }
        found = true;
        if (out == null) return true;
        out.append(text, copied, i)
            .append(digits, 0, 6)
            .append("*".repeat(matchLength - 10))
            .append(digits, matchLength - 4, 4);
        copied = matchEnd;
        i = matchEnd;
      }
      if (found && out != null) out.append(text, copied, n);
      return found;
    } finally {
      Arrays.fill(digits, '\0');
    }
  }

  /** Caracteres separadores en {@code from}, o 0 cuando no hay o hay más de los permitidos. */
  private static int separatorRun(CharSequence text, int from) {
    int run = 0;
    while (from + run < text.length() && separator(text.charAt(from + run))) {
      if (++run > MAX_SEPARATOR_RUN) return 0;
    }
    return run;
  }

  private static boolean separator(char c) {
    return switch (c) {
      case ' ',
          '\t',
          '\u00A0',
          '\u2007',
          '\u202F',
          '\u3000',
          '-',
          '.',
          '/',
          '_',
          '\u2010',
          '\u2011',
          '\u2012',
          '\u2013',
          '\u2014',
          '\u2212',
          '\uFF0D',
          '\uFF0E',
          '\uFF0F' ->
          true;
      default -> false;
    };
  }

  private static boolean digit(char c) {
    return Character.isDigit(c);
  }
}
