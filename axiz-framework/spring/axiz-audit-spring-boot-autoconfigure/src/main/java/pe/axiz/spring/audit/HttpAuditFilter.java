package pe.axiz.spring.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import pe.axiz.core.audit.AuditTrail;

/**
 * Audita las solicitudes HTTP que modifican estado. El recurso es el patrón de ruta coincidente
 * (acotado), nunca la ruta sin procesar; el resultado sigue al estado de la respuesta.
 */
public class HttpAuditFilter extends OncePerRequestFilter implements Ordered {

  private final AuditTrail trail;
  private final AuditContextResolver context;
  private final HttpAuditRules rules;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public HttpAuditFilter(
      AuditTrail trail, AuditContextResolver context, AxizAuditProperties.Http http) {
    this.trail = Objects.requireNonNull(trail);
    this.context = Objects.requireNonNull(context);
    this.rules = new HttpAuditRules(http);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try (var boundary = PendingAuditFailures.open()) {
      if (!rules.audited(
          request.getMethod(),
          request.getRequestURI().substring(request.getContextPath().length()))) {
        chain.doFilter(request, response);
        return;
      }
      AuditTrail.Context ctx = context.current(request.getRemoteAddr());
      boolean failed = true;
      try {
        chain.doFilter(request, response);
        failed = response.getStatus() >= 400;
      } finally {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        AuditTrail.Builder record =
            trail
                .record(
                    "http." + request.getMethod().toLowerCase(Locale.ROOT),
                    pattern != null ? pattern.toString() : "unmatched")
                .context(ctx)
                .detail("status", response.getStatus());
        try {
          if (failed) {
            record.failure("HTTP-" + response.getStatus());
          } else {
            record.success();
          }
        } catch (RuntimeException e) {
          logger.warn(
              "Audit event could not be recorded; failureType=" + e.getClass().getSimpleName());
        }
      }
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
