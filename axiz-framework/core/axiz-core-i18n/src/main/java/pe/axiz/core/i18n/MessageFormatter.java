package pe.axiz.core.i18n;

import java.util.Map;

/**
 * Reemplaza los marcadores {@code {name}}. Los marcadores desconocidos se dejan intactos; una llave
 * de apertura o de cierre sin nombre se conserva de forma literal. Los valores se generan con
 * {@code String.valueOf}.
 */
public final class MessageFormatter {

  private MessageFormatter() {}

  public static String format(String template, Map<String, ?> parameters) {
    if (parameters.isEmpty() || template.indexOf('{') < 0) {
      return template;
    }
    StringBuilder out = new StringBuilder(template.length() + 16);
    int i = 0;
    while (i < template.length()) {
      char c = template.charAt(i);
      if (c == '{') {
        int end = template.indexOf('}', i + 1);
        if (end > i + 1) {
          String name = template.substring(i + 1, end);
          if (parameters.containsKey(name)) {
            out.append(parameters.get(name));
            i = end + 1;
            continue;
          }
        }
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }
}
