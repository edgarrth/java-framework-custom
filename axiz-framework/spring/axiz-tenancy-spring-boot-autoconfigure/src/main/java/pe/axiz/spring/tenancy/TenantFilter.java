package pe.axiz.spring.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.core.tenancy.TenantContext;

/**
 * Binds the tenant for the request (runs after authentication). Missing tenants are rejected with a
 * 400 problem when tenancy is required.
 */
public class TenantFilter extends OncePerRequestFilter {

  private final TenantFilterSupport support;

  public TenantFilter(TenantFilterSupport support) {
    this.support = Objects.requireNonNull(support);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return support.skip(request.getRequestURI().substring(request.getContextPath().length()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    RequestMetadata metadata = RequestContext.get();
    Optional<String> tenant = support.resolve(metadata, request::getHeader);
    if (tenant.isEmpty()) {
      if (support.required()) {
        throw new ValidationException(
            java.util.List.of(FieldViolation.of("tenant", "Required", "tenant is required")));
      }
      chain.doFilter(request, response);
      return;
    }
    String t = tenant.get();
    MDC.put("tenant", t);
    TenantContext.restore(t);
    RequestContext.Scope scope =
        metadata != null ? RequestContext.open(metadata.withTenant(t)) : null;
    try {
      chain.doFilter(request, response);
    } finally {
      if (scope != null) {
        scope.close();
      }
      TenantContext.restore(null);
      MDC.remove("tenant");
    }
  }
}
