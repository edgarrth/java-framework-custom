package pe.axiz.core.sensitive;

import pe.axiz.core.identifiers.IdentifierMasks;

/**
 * Funciones de enmascaramiento. Todas son totales: cualquier entrada (incluso vacía) da una salida
 * segura.
 */
public final class Masker {

  /** Reemplazo fijo para valores enmascarados por completo; no revela la longitud. */
  public static final String FULL_MASK = IdentifierMasks.FULL;

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
    return IdentifierMasks.tail(value, keep);
  }

  static String partial(String value) {
    if (value.length() < 8) return FULL_MASK;
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
    long shown = out.chars().filter(c -> c != '*').count();
    return value.length() < 8 || shown > value.length() / 4 ? FULL_MASK : out.toString();
  }

  static String email(String value) {
    return IdentifierMasks.email(value);
  }

  static String card(String value) {
    return IdentifierMasks.card(value);
  }
}
