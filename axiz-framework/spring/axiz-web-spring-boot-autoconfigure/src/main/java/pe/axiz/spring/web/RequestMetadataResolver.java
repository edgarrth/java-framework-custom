package pe.axiz.spring.web;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.api.HeaderCatalog;
import pe.axiz.core.api.HeaderDefinition;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.id.CorrelationIds;

/**
 * Construye {@link RequestMetadata} a partir de una solicitud entrante y valida el catálogo de
 * cabeceras. Lo comparten el filtro servlet y el web filter reactivo.
 */
public final class RequestMetadataResolver {

  private final HeaderCatalog catalog;
  private final List<PathPattern> excluded;
  private final @Nullable String channelHeader;
  private final Clock clock;

  public RequestMetadataResolver(
      HeaderCatalog catalog,
      List<String> excludedPaths,
      @Nullable String channelHeader,
      Clock clock) {
    this.catalog = Objects.requireNonNull(catalog);
    PathPatternParser parser = PathPatternParser.defaultInstance;
    this.excluded = excludedPaths.stream().map(parser::parse).toList();
    this.channelHeader = channelHeader;
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Indica si la ruta omite la validación de cabeceras (coincidencia con {@code PathPattern}, nunca
   * con subcadenas).
   */
  public boolean isExcluded(String path) {
    PathContainer container = PathContainer.parsePath(path);
    return excluded.stream().anyMatch(p -> p.matches(container));
  }

  /** Nombres de las cabeceras del catálogo ausentes o inválidas; vacío para las rutas excluidas. */
  public List<String> invalidHeaders(String path, Function<String, @Nullable String> headers) {
    if (isExcluded(path)) {
      return List.of();
    }
    return catalog.definitions().stream()
        .filter(d -> d.validate(headers.apply(d.name())) != null)
        .map(HeaderDefinition::name)
        .toList();
  }

  public RequestMetadata resolve(Function<String, @Nullable String> headers, Locale locale) {
    String correlationId =
        CorrelationIds.reuseOrCreate(headers.apply(StandardHeaders.CORRELATION_ID));
    String requestId = CorrelationIds.reuseOrCreate(headers.apply(StandardHeaders.REQUEST_ID));
    return RequestMetadata.builder(correlationId, requestId)
        .channel(channelHeader != null ? headers.apply(channelHeader) : null)
        .locale(locale)
        .receivedAt(clock.instant())
        .attributes(catalog.propagated(headers))
        .build();
  }

  public HeaderCatalog catalog() {
    return catalog;
  }
}
