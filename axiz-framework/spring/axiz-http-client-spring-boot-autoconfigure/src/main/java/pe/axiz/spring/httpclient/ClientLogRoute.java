package pe.axiz.spring.httpclient;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Diagnóstico estable de rutas; las rutas expandidas y los valores de la query nunca se usan como
 * respaldo en los logs.
 */
final class ClientLogRoute {
  private ClientLogRoute() {}

  static String of(@Nullable Object template, URI configuredBase) {
    if (template instanceof String text
        && text.length() <= 2048
        && text.chars().noneMatch(Character::isISOControl)) {
      try {
        String path = UriComponentsBuilder.fromUriString(text).build().getPath();
        // Una URI de texto literal ya puede contener datos del invocante. Solo se conservan las
        // plantillas con variables.
        if (path != null && path.contains("{") && path.contains("}")) return path;
      } catch (IllegalArgumentException ignored) {
        // Los metadatos de plantilla inválidos no son motivo para revelar la URI expandida.
      }
    }
    return configuredBase.getHost() + " [no-template]";
  }
}
