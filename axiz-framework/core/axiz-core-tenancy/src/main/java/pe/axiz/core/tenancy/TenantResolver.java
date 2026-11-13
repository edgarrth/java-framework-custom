package pe.axiz.core.tenancy;

import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the tenant of an inbound request: first from the validated token claim, then (only when
 * explicitly trusted, e.g. set by the platform gateway) from a header.
 */
public final class TenantResolver {

  private final boolean trustHeader;
  private final String header;

  public TenantResolver(boolean trustHeader, String header) {
    this.trustHeader = trustHeader;
    this.header = header;
  }

  /**
   * Resolves the tenant.
   *
   * @param tokenTenant tenant claim of the validated token, if any
   * @param headers header lookup
   * @return the tenant when present and valid
   */
  public Optional<String> resolve(
      @Nullable String tokenTenant, Function<String, @Nullable String> headers) {
    // explicit null checks: the null analysis (NullAway) does not look inside isValid
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
