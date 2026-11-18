package pe.axiz.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Minimal JSON writer for maps, collections, strings, numbers, booleans, enums, dates and UUIDs.
 * Used where layer 1 must produce JSON without depending on a JSON library (audit records,
 * structured payloads). Anything else is written with {@code toString()} as a string.
 */
public final class JsonWriter {

  private JsonWriter() {}

  public static String write(@Nullable Object value) {
    StringBuilder sb = new StringBuilder(128);
    append(sb, value, 0);
    return sb.toString();
  }

  /**
   * Deterministic JSON for audit snapshots. Keys use String.compareTo; finite Float values are
   * widened to double; bounded BigDecimal values use plain notation. Unknown types and non-string
   * keys are rejected without invoking their toString methods. This is Axiz canonical JSON v1, not
   * the different number/ordering format defined by RFC 8785.
   */
  public static String writeCanonical(@Nullable Object value) {
    StringBuilder out = new StringBuilder(128);
    canonical(out, value, 0);
    return out.toString();
  }

  /** Exact UTF-8 byte length of the escaped text, excluding its surrounding quotes. */
  public static int escapedUtf8Length(String text) {
    long length = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f') {
        length += 2;
      } else if (c < 0x20 || c == '\u2028' || c == '\u2029') {
        length += 6;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        length += 4;
        i++;
      } else if (Character.isSurrogate(c)) {
        length += 6;
      } else {
        length += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
      }
    }
    return Math.toIntExact(length);
  }

  /** Exact UTF-8 byte length of a supported canonical scalar. */
  public static int canonicalLength(@Nullable Object scalar) {
    if (scalar instanceof String text) return Math.addExact(2, escapedUtf8Length(text));
    return scalar(scalar).length(); // all other scalar forms are ASCII
  }

  private static void canonical(StringBuilder out, @Nullable Object value, int depth) {
    if (depth > JsonReader.MAX_DEPTH) throw new IllegalArgumentException("JSON nesting too deep");
    if (value instanceof Map<?, ?> map) {
      Map<String, @Nullable Object> sorted = new TreeMap<>();
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key))
          throw new IllegalArgumentException("Canonical JSON requires string keys");
        if (sorted.containsKey(key))
          throw new IllegalArgumentException("Duplicate canonical JSON key");
        sorted.put(key, entry.getValue());
      }
      out.append('{');
      boolean first = true;
      for (var entry : sorted.entrySet()) {
        if (!first) out.append(',');
        first = false;
        string(out, entry.getKey());
        out.append(':');
        canonical(out, entry.getValue(), depth + 1);
      }
      out.append('}');
    } else if (value instanceof Collection<?> collection) {
      out.append('[');
      boolean first = true;
      for (Object element : collection) {
        if (!first) out.append(',');
        first = false;
        canonical(out, element, depth + 1);
      }
      out.append(']');
    } else if (value instanceof String text) {
      string(out, text);
    } else {
      out.append(scalar(value));
    }
  }

  private static String scalar(@Nullable Object value) {
    if (value == null) return "null";
    Class<?> type = value.getClass();
    if (type == Boolean.class
        || type == Byte.class
        || type == Short.class
        || type == Integer.class
        || type == Long.class) return value.toString();
    if (type == Double.class && Double.isFinite((Double) value))
      return Double.toString((Double) value);
    if (type == Float.class && Float.isFinite((Float) value))
      return Double.toString(((Float) value).doubleValue());
    if (type == BigInteger.class && ((BigInteger) value).bitLength() <= 256)
      return value.toString();
    if (type == BigDecimal.class) {
      var decimal = (BigDecimal) value;
      if (decimal.precision() <= 64 && decimal.scale() >= -64 && decimal.scale() <= 64)
        return decimal.toPlainString();
    }
    throw new IllegalArgumentException("Unsupported canonical JSON scalar");
  }

  private static void append(StringBuilder sb, @Nullable Object value, int depth) {
    if (depth > JsonReader.MAX_DEPTH) {
      throw new IllegalArgumentException("JSON nesting too deep");
    }
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Boolean b) {
      sb.append(b);
    } else if (value instanceof BigDecimal bd) {
      sb.append(bd.toString());
    } else if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
      sb.append("null");
    } else if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
      sb.append("null");
    } else if (value instanceof Number n) {
      sb.append(n);
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        string(sb, String.valueOf(e.getKey()));
        sb.append(':');
        append(sb, e.getValue(), depth + 1);
      }
      sb.append('}');
    } else if (value instanceof Collection<?> c) {
      sb.append('[');
      boolean first = true;
      for (Object item : c) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        append(sb, item, depth + 1);
      }
      sb.append(']');
    } else if (value instanceof Object[] array) {
      append(sb, Arrays.asList(array), depth);
    } else {
      // text, enums, UUIDs, java.time values, characters and anything else: their text form
      string(sb, value.toString());
    }
  }

  private static void string(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (Character.isHighSurrogate(c)
              && i + 1 < s.length()
              && Character.isLowSurrogate(s.charAt(i + 1))) {
            sb.append(c).append(s.charAt(++i));
          } else if (c < 0x20 || c == ' ' || c == ' ' || Character.isSurrogate(c)) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
