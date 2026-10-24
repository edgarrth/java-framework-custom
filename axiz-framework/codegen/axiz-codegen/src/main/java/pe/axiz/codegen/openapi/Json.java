package pe.axiz.codegen.openapi;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Writes YAML-loaded values (maps, lists, scalars) as compact JSON. */
final class Json {

  private Json() {}

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
    } else if (v instanceof List<?> l) {
      b.append('[');
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) {
          b.append(',');
        }
        append(b, l.get(i));
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
