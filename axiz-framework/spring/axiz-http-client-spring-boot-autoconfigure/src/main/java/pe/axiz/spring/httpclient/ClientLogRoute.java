package pe.axiz.spring.httpclient;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriComponentsBuilder;

/** Stable route diagnostics; expanded paths and query values never become log fallbacks. */
final class ClientLogRoute {
  private ClientLogRoute() {}

  static String of(@Nullable Object template, URI configuredBase) {
    if (template instanceof String text
        && text.length() <= 2048
        && text.chars().noneMatch(Character::isISOControl)) {
      try {
        String path = UriComponentsBuilder.fromUriString(text).build().getPath();
        // A literal string URI can already contain caller data. Only keep variable templates.
        if (path != null && path.contains("{") && path.contains("}")) return path;
      } catch (IllegalArgumentException ignored) {
        // Invalid template metadata is not a reason to disclose the expanded URI.
      }
    }
    return configuredBase.getHost() + " [no-template]";
  }
}
