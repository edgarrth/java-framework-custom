package pe.axiz.spring.security;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.web.SecurityFilterChain;

/** Identifies the chain whose rules were actually compiled by this auto-configuration. */
final class VerifiedServletSecurityChain implements SecurityFilterChain {
  private final SecurityFilterChain delegate;

  VerifiedServletSecurityChain(SecurityFilterChain delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean matches(HttpServletRequest request) {
    return delegate.matches(request);
  }

  @Override
  public List<Filter> getFilters() {
    return delegate.getFilters();
  }
}
