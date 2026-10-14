package pe.axiz.spring.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.security.AxizPrincipal;

/**
 * Adds the authenticated caller (client, subject, tenant) to the request context after token
 * validation, so logs, audit, metrics and outbound calls see it.
 */
public class CallerContextFilter extends OncePerRequestFilter {

  static final String MDC_CLIENT = "clientId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    RequestMetadata current = RequestContext.get();
    if (!(auth instanceof AxizAuthentication a) || current == null) {
      chain.doFilter(request, response);
      return;
    }
    AxizPrincipal p = a.axizPrincipal();
    MDC.put(MDC_CLIENT, p.clientId());
    try (RequestContext.Scope ignored =
        RequestContext.open(
            current.withCaller(p.clientId(), p.subject(), p.tenantId(), p.issuer()))) {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_CLIENT);
    }
  }
}
