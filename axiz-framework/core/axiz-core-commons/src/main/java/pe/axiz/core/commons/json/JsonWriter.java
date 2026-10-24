package pe.axiz.core.commons.json;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
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

  private static void append(StringBuilder sb, @Nullable Object value, int depth) {
    if (depth > 32) {
      throw new IllegalArgumentException("JSON nesting too deep");
    }
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Boolean b) {
      sb.append(b);
    } else if (value instanceof BigDecimal bd) {
      sb.append(bd.toPlainString());
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
      append(sb, java.util.Arrays.asList(array), depth);
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
          if (c < 0x20 || c == ' ' || c == ' ') {
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
