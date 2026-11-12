package pe.axiz.spring.foundation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** Safe configuration diagnostics shared by infrastructure auto-configurations. */
public final class ConfigurationErrors {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)");

  private ConfigurationErrors() {}

  /** Describes logical/physical routing and missing variables, excluding URI credentials/query. */
  public static String destination(String logical, String resolved) {
    var missing =
        PLACEHOLDER.matcher(resolved).results().map(match -> match.group(1)).distinct().toList();
    return "destination '"
        + address(logical)
        + "' resolves to '"
        + address(resolved)
        + "'"
        + (missing.isEmpty() ? "" : "; unresolved variables: " + missing);
  }

  /** A safe property hint; display syntax does not dictate how scalar maps are bound. */
  public static String destinationProperty(String prefix, String logical) {
    if (!logical.matches("[A-Za-z0-9_.-]+")) return prefix + " (see destination above)";
    return logical.contains(".") ? prefix + "[" + logical + "]" : prefix + "." + logical;
  }

  private static String address(String value) {
    if (!value.contains("://")) return value;
    try {
      var uri = new URI(value);
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toString();
    } catch (URISyntaxException invalid) {
      return "<invalid URI>";
    }
  }
}
