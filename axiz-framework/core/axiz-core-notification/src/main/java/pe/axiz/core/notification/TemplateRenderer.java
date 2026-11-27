package pe.axiz.core.notification;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Genera los marcadores {@code {{name}}}. Los valores de email (HTML) se escapan; los parámetros
 * faltantes fallan en lugar de enviar un mensaje roto.
 */
public final class TemplateRenderer {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

  private TemplateRenderer() {}

  public static String render(
      String template, Map<String, String> parameters, NotificationChannel channel) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = parameters.get(m.group(1));
      if (value == null) {
        throw new IllegalArgumentException("Missing template parameter " + m.group(1));
      }
      m.appendReplacement(
          out,
          Matcher.quoteReplacement(channel == NotificationChannel.EMAIL ? html(value) : value));
    }
    m.appendTail(out);
    return out.toString();
  }

  static String html(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '&' -> b.append("&amp;");
        case '"' -> b.append("&quot;");
        case '\'' -> b.append("&#39;");
        default -> b.append(c);
      }
    }
    return b.toString();
  }
}
