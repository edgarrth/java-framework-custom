package pe.axiz.core.tenancy;

import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Resuelve el tenant de una petición entrante: primero a partir del claim del token validado y
 * luego (solo cuando es de confianza explícita, por ejemplo fijada por el gateway de la plataforma)
 * a partir de una cabecera.
 */
public final class TenantResolver {

  private final boolean trustHeader;
  private final String header;

  public TenantResolver(boolean trustHeader, String header) {
    this.trustHeader = trustHeader;
    this.header = header;
  }

  /**
   * Resuelve el tenant.
   *
   * @param tokenTenant claim de tenant del token validado, si existe
   * @param headers búsqueda de cabeceras
   * @return el tenant cuando está presente y es válido
   */
  public Optional<String> resolve(
      @Nullable String tokenTenant, Function<String, @Nullable String> headers) {
    // verificaciones de null explícitas: el análisis de nulos (NullAway) no mira dentro de isValid
    if (tokenTenant != null) {
      if (!TenantContext.isValid(tokenTenant)) throw new InvalidTenantException();
      return Optional.of(tokenTenant);
    }
    if (trustHeader) {
      String fromHeader = headers.apply(header);
      if (fromHeader != null && TenantContext.isValid(fromHeader)) {
        return Optional.of(fromHeader);
      }
    }
    return Optional.empty();
  }

  public String header() {
    return header;
  }
}
