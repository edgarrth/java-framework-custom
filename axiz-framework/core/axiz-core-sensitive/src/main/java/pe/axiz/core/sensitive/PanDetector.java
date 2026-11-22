package pe.axiz.core.sensitive;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.identifiers.CheckDigits;

/** Bounded PAN candidate detection; Luhn matches are a heuristic, not a data classification. */
public final class PanDetector {

  public static final int MIN_DIGITS = 12;
  public static final int MAX_DIGITS = 19;

  private PanDetector() {}

  /** True for 12-19 digits that pass Luhn. */
  public static boolean isPan(String digits) {
    return digits.length() >= MIN_DIGITS
        && digits.length() <= MAX_DIGITS
        && CheckDigits.allDigits(digits)
        && CheckDigits.luhn(digits);
  }

  /**
   * A whole number that is a card number becomes its truncated text; anything else is returned
   * unchanged. Only exact JDK types are inspected: no toString() of unknown Number classes.
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
      double v = value.doubleValue(); // whole numbers below 2^53 are exact in a double
      digits =
          v >= 1e11 && v < 9_007_199_254_740_992d && v == Math.rint(v)
              ? Long.toString((long) v)
              : null;
    }
    return digits != null && isPan(digits) ? truncate(digits) : value;
  }

  /** Digits of a positive decimal that is mathematically whole, whatever its scale; else null. */
  private static @Nullable String wholeDigits(BigDecimal v) {
    if (v.signum() <= 0 || v.precision() > 64) { // bound the work before normalizing
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

  /** Longest run of separator characters accepted between two digit groups. */
  private static final int MAX_SEPARATOR_RUN = 2;

  /**
   * Replaces every 12-19 digit run that passes Luhn. Digits may be any Unicode decimal digit
   * (fullwidth, Arabic-Indic...) and groups may be joined by up to two separator characters:
   * spaces, tabs, no-break spaces, hyphens and dashes, {@code .}, {@code /} or {@code _}. The kept
   * first six and last four digits are written as ASCII.
   */
  public static String redact(String text) {
    StringBuilder out = new StringBuilder(text.length());
    return scan(text, out) ? out.toString() : text;
  }

  /** Whether the bounded digit scanner finds any PAN-like candidate. */
  public static boolean containsPan(String text) {
    return scan(text, null);
  }

  /** Detects the same candidates as {@link #redact(String)} without copying the input. */
  public static boolean containsPan(char[] text) {
    return scan(CharBuffer.wrap(text), null);
  }

  /**
   * Scans {@code text}; when {@code out} is not null it receives the redacted text. Returns whether
   * a candidate was found (with a null {@code out} it stops at the first one).
   */
  private static boolean scan(CharSequence text, @Nullable StringBuilder out) {
    int n = text.length();
    char[] digits = new char[MAX_DIGITS + 1];
    boolean found = false;
    int copied = 0; // text[0, copied) is already in out
    try {
      int i = 0;
      while (i < n) {
        if (!digit(text.charAt(i)) || (i > 0 && digit(text.charAt(i - 1)))) {
          i++;
          continue;
        }
        // Candidate: this digit group plus the following ones joined by short separator runs.
        int count = 0;
        int matchEnd = -1; // exclusive end of the longest valid PAN
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
          i++; // try again from the next group
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

  /** Separator characters at {@code from}, or 0 when there are none or more than allowed. */
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
