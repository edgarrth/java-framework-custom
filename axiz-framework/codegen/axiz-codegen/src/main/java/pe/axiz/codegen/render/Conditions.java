package pe.axiz.codegen.render;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Tiny condition language of template manifests: {@code a.b && !c || d}. */
final class Conditions {

  private Conditions() {}

  static boolean test(@Nullable String expression, Map<String, Object> context) {
    if (expression == null || expression.isBlank()) {
      return true;
    }
    for (String any : expression.split("\\|\\|", -1)) {
      boolean all = true;
      for (String term : any.split("&&", -1)) {
        String t = term.strip();
        boolean negate = t.startsWith("!");
        boolean value = truthy(resolve(negate ? t.substring(1).strip() : t, context));
        if (negate == value) {
          all = false;
          break;
        }
      }
      if (all) {
        return true;
      }
    }
    return false;
  }

  static @Nullable Object resolve(String path, Map<String, Object> context) {
    Object current = context;
    for (String part : path.split("\\.", -1)) {
      if (!(current instanceof Map<?, ?> m)) {
        return null;
      }
      current = m.get(part);
    }
    return current;
  }

  static boolean truthy(@Nullable Object v) {
    if (v == null) {
      return false;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof String s) {
      return !s.isEmpty();
    }
    if (v instanceof Collection<?> c) {
      return !c.isEmpty();
    }
    return true;
  }

  static List<?> list(@Nullable Object v) {
    return v instanceof List<?> l ? l : List.of();
  }
}
