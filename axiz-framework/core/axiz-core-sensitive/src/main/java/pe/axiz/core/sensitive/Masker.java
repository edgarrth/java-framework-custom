package pe.axiz.core.sensitive;

/** Masking functions. All of them are total: any input (even empty) gives a safe output. */
public final class Masker {

  /** Fixed replacement for fully masked values; does not reveal the length. */
  public static final String FULL_MASK = "****";

  private Masker() {}

  public static String mask(String value, MaskType type) {
    if (value == null) {
      return FULL_MASK;
    }
    return switch (type) {
      case NONE -> value;
      case FULL -> FULL_MASK;
      case EMAIL -> email(value);
      case PHONE -> keepLast(value, 3);
      case DOCUMENT -> keepLast(value, 4);
      case CARD -> card(value);
      case PARTIAL -> partial(value);
      case NAME -> name(value);
    };
  }

  static String keepLast(String value, int keep) {
    if (value.length() <= keep) {
      return "*".repeat(value.length());
    }
    return "*".repeat(value.length() - keep) + value.substring(value.length() - keep);
  }

  static String partial(String value) {
    if (value.length() <= 2) {
      return "*".repeat(value.length());
    }
    return value.charAt(0) + "*".repeat(value.length() - 2) + value.charAt(value.length() - 1);
  }

  static String name(String value) {
    StringBuilder out = new StringBuilder(value.length());
    boolean wordStart = true;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isWhitespace(c) || c == '-') {
        out.append(c);
        wordStart = true;
      } else {
        out.append(wordStart ? c : '*');
        wordStart = false;
      }
    }
    return out.toString();
  }

  static String email(String value) {
    int at = value.indexOf('@');
    if (at <= 0) {
      return partial(value);
    }
    return value.charAt(0) + "*".repeat(Math.max(at - 1, 1)) + value.substring(at);
  }

  static String card(String value) {
    String digits = value.replaceAll("\\D", "");
    if (digits.length() < 13) {
      return keepLast(digits, 4);
    }
    return digits.substring(0, 6)
        + "*".repeat(digits.length() - 10)
        + digits.substring(digits.length() - 4);
  }
}
