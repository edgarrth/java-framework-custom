package pe.axiz.core.sensitive;

/**
 * Masks values of sensitive keys inside raw JSON text (HTTP bodies in logs) without parsing it.
 * Handles string, number and boolean values; nested objects are traversed because every key is
 * matched wherever it appears.
 *
 * <p>The text is read by a single forward scan, in time linear in its length and with constant
 * stack. A regular expression is not used on purpose: the body comes from the caller, and the
 * {@code java.util.regex} engine recurses once per character inside a repeated group, so a string
 * value of a few thousand characters (a document in base64, for example) overflowed the stack while
 * the request was being logged.
 *
 * <p>A pair is a key of 1 to 100 characters without quotes or backslashes, optional whitespace, a
 * colon, optional whitespace and a value: a string (with escapes), a number ({@code -12}, {@code
 * 10.5}, {@code 1e-3}), {@code true} or {@code false}. Anything else is copied as it is.
 */
public final class JsonTextMasker {

  private static final int MAX_KEY = 100;

  private final ObjectMasker masker;

  public JsonTextMasker(ObjectMasker masker) {
    this.masker = masker;
  }

  public String mask(String json, OutputChannel channel) {
    StringBuilder out = new StringBuilder(json.length());
    int copied = 0;
    int from = 0;
    int open;
    while ((open = json.indexOf('"', from)) >= 0) {
      int pairEnd = pairEnd(json, open);
      if (pairEnd < 0) {
        from = open + 1;
        continue;
      }
      int keyEnd = json.indexOf('"', open + 1);
      String key = json.substring(open + 1, keyEnd);
      int valueStart = skipWhitespace(json, skipWhitespace(json, keyEnd + 1) + 1);
      String rawValue = json.substring(valueStart, pairEnd);
      boolean quoted = rawValue.charAt(0) == '"';
      String value = quoted ? rawValue.substring(1, rawValue.length() - 1) : rawValue;
      String masked = masker.maskByKey(key, value, channel);
      out.append(json, copied, open);
      if (masked == null || masked.equals(value)) {
        out.append(json, open, pairEnd);
      } else {
        out.append('"').append(key).append("\":\"").append(masked).append('"');
      }
      copied = pairEnd;
      from = pairEnd;
    }
    out.append(json, copied, json.length());
    return out.toString();
  }

  /** End (exclusive) of the pair that starts at the quote {@code open}, or -1 if none does. */
  private static int pairEnd(String s, int open) {
    int keyEnd = open + 1;
    while (keyEnd < s.length() && keyEnd - open - 1 <= MAX_KEY) {
      char c = s.charAt(keyEnd);
      if (c == '"') {
        break;
      }
      if (c == '\\') {
        return -1;
      }
      keyEnd++;
    }
    int keyLength = keyEnd - open - 1;
    if (keyEnd >= s.length() || keyLength < 1 || keyLength > MAX_KEY) {
      return -1;
    }
    int colon = skipWhitespace(s, keyEnd + 1);
    if (colon >= s.length() || s.charAt(colon) != ':') {
      return -1;
    }
    return valueEnd(s, skipWhitespace(s, colon + 1));
  }

  private static int valueEnd(String s, int start) {
    if (start >= s.length()) {
      return -1;
    }
    char first = s.charAt(start);
    if (first == '"') {
      return stringEnd(s, start);
    }
    if (first == '-' || isDigit(first)) {
      return numberEnd(s, start);
    }
    if (s.startsWith("true", start)) {
      return start + 4;
    }
    if (s.startsWith("false", start)) {
      return start + 5;
    }
    return -1;
  }

  /** A quoted string with escapes; an escaped line break is not part of a JSON string. */
  private static int stringEnd(String s, int start) {
    int i = start + 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"') {
        return i + 1;
      }
      if (c == '\\') {
        if (i + 1 >= s.length() || isLineTerminator(s.charAt(i + 1))) {
          return -1;
        }
        i += 2;
      } else {
        i++;
      }
    }
    return -1;
  }

  /** {@code -?digits(.digits)?([eE][+-]?digits)?}, the longest prefix that forms a number. */
  private static int numberEnd(String s, int start) {
    int i = start;
    if (s.charAt(i) == '-') {
      i++;
    }
    int integer = digits(s, i);
    if (integer == i) {
      return -1;
    }
    i = integer;
    if (i < s.length() && s.charAt(i) == '.') {
      int fraction = digits(s, i + 1);
      if (fraction > i + 1) {
        i = fraction;
      }
    }
    if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      int sign = i + 1;
      if (sign < s.length() && (s.charAt(sign) == '+' || s.charAt(sign) == '-')) {
        sign++;
      }
      int exponent = digits(s, sign);
      if (exponent > sign) {
        i = exponent;
      }
    }
    return i;
  }

  private static int digits(String s, int from) {
    int i = from;
    while (i < s.length() && isDigit(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipWhitespace(String s, int from) {
    int i = from;
    while (i < s.length() && isWhitespace(s.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
  }

  private static boolean isLineTerminator(char c) {
    return c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ';
  }
}
