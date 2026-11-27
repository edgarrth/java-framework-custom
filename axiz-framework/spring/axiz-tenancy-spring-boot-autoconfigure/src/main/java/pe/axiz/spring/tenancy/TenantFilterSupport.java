package pe.axiz.spring.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.tenancy.TenantResolver;

/** Lógica compartida por los filtros de tenant servlet y reactivo. */
public final class TenantFilterSupport {

  private final TenantResolver resolver;
  private final boolean required;
  private final List<PathPattern> excluded;

  public TenantFilterSupport(AxizTenancyProperties p) {
    this.resolver = new TenantResolver(p.trustHeader(), p.header());
    this.required = p.required();
    this.excluded =
        p.excludedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
  }

  public boolean skip(String path) {
    PathContainer c = PathContainer.parsePath(path);
    return excluded.stream().anyMatch(x -> x.matches(c));
  }

  /**
   * Tenant tomado del token (ya presente en los metadatos de la solicitud) o de la cabecera de
   * confianza.
   */
  public Optional<String> resolve(
      @Nullable RequestMetadata metadata, Function<String, @Nullable String> headers) {
    return resolver.resolve(metadata != null ? metadata.tenantId() : null, headers);
  }

  public boolean required() {
    return required;
  }
}
