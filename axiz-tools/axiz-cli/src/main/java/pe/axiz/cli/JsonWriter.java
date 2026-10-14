package pe.axiz.cli;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Minimal JSON writer for the initializr requests (no reflection, native friendly). */
final class JsonWriter {

  private JsonWriter() {}

  static String write(@Nullable Object value) {
    StringBuilder b = new StringBuilder();
    append(b, value);
    return b.toString();
  }

  private static void append(StringBuilder b, @Nullable Object v) {
    if (v == null) {
      b.append("null");
    } else if (v instanceof Map<?, ?> m) {
      b.append('{');
      Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<?, ?> e = it.next();
        string(b, String.valueOf(e.getKey()));
        b.append(':');
        append(b, e.getValue());
        if (it.hasNext()) {
          b.append(',');
        }
      }
      b.append('}');
    } else if (v instanceof Collection<?> c) {
      b.append('[');
      Iterator<?> it = c.iterator();
      while (it.hasNext()) {
        append(b, it.next());
        if (it.hasNext()) {
          b.append(',');
        }
      }
      b.append(']');
    } else if (v instanceof Number || v instanceof Boolean) {
      b.append(v);
    } else {
      string(b, String.valueOf(v));
    }
  }

  private static void string(StringBuilder b, String s) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    b.append('"');
  }
}
