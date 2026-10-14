package pe.axiz.spring.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.axiz.core.security.RateLimiter;

/**
 * Per-caller rate limit after authentication. Answers 429 with {@code Retry-After}; exposes {@code
 * RateLimit-Remaining} on allowed requests.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimiter limiter;
  private final RateLimitRules rules;
  private final SecurityProblems problems;

  public RateLimitFilter(RateLimiter limiter, RateLimitRules rules, SecurityProblems problems) {
    this.limiter = Objects.requireNonNull(limiter);
    this.rules = Objects.requireNonNull(rules);
    this.problems = Objects.requireNonNull(problems);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String path = request.getRequestURI().substring(request.getContextPath().length());
    Optional<AxizSecurityProperties.Rule> rule = rules.match(path);
    if (!(auth instanceof AxizAuthentication a) || rule.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    AxizSecurityProperties.Rule r = rule.get();
    RateLimiter.Decision d =
        limiter.tryAcquire(RateLimitRules.key(r, a.axizPrincipal()), r.limit(), r.window());
    if (!d.allowed()) {
      response.setHeader("Retry-After", Long.toString(Math.max(1, d.retryAfter().toSeconds())));
      ProblemAuthenticationEntryPoint.write(
          response,
          429,
          problems.tooManyRequests(LocaleContextHolder.getLocale(), request.getRequestURI()));
      return;
    }
    response.setHeader("RateLimit-Remaining", Long.toString(d.remaining()));
    chain.doFilter(request, response);
  }
}
