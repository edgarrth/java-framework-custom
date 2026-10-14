package pe.axiz.core.notification;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders {@code {{name}}} placeholders. Email (HTML) values are escaped; missing parameters fail
 * instead of sending a broken message.
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
    for (char c : s.toCharArray()) {
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
