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

  /** Replaces every 12-19 digit run (single spaces or hyphens allowed inside) that passes Luhn. */
  public static String redact(String text) {
    int n = text.length();
    StringBuilder out = null;
    int copied = 0; // text[0, copied) is already in out
    int i = 0;
    while (i < n) {
      boolean groupStart = digit(text.charAt(i)) && (i == 0 || !digit(text.charAt(i - 1)));
      if (!groupStart) {
        i++;
        continue;
      }
      // Candidate: this digit group plus the following ones joined by one space or hyphen.
      StringBuilder digits = new StringBuilder(20);
      int matchEnd = -1; // exclusive end of the longest valid PAN
      int matchLength = 0;
      int j = i;
      while (j < n && digits.length() <= 19) {
        char c = text.charAt(j);
        if (digit(c)) {
          digits.append(c);
          j++;
          boolean groupEnds = j == n || !digit(text.charAt(j));
          if (groupEnds && isPan(digits.toString())) {
            matchEnd = j;
            matchLength = digits.length();
          }
        } else if ((c == ' ' || c == '-') && j + 1 < n && digit(text.charAt(j + 1))) {
          j++;
        } else {
          break;
        }
      }
      if (matchEnd < 0) {
        i++; // try again from the next group
        continue;
      }
      if (out == null) {
        out = new StringBuilder(n);
      }
      out.append(text, copied, i)
          .append(digits, 0, 6)
          .append("*".repeat(matchLength - 10))
          .append(digits, matchLength - 4, matchLength);
      copied = matchEnd;
      i = matchEnd;
    }
    return out == null ? text : out.append(text, copied, n).toString();
  }

  /** Whether the bounded digit scanner finds any PAN-like candidate. */
  public static boolean containsPan(String text) {
    return !text.equals(redact(text));
  }

  /** Detects the same bounded digit groups without allocating immutable plaintext copies. */
  public static boolean containsPan(char[] text) {
    char[] digits = new char[MAX_DIGITS];
    try {
      for (int start = 0; start < text.length; start++) {
        if (!digit(text[start]) || (start > 0 && digit(text[start - 1]))) continue;
        int count = 0;
        for (int j = start; j < text.length && count < MAX_DIGITS; j++) {
          char c = text[j];
          if (digit(c)) {
            digits[count++] = c;
            if ((j + 1 == text.length || !digit(text[j + 1]))
                && count >= MIN_DIGITS
                && CheckDigits.luhn(CharBuffer.wrap(digits, 0, count))) return true;
          } else if (!((c == ' ' || c == '-') && j + 1 < text.length && digit(text[j + 1]))) break;
        }
      }
      return false;
    } finally {
      Arrays.fill(digits, '\0');
    }
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }
}
