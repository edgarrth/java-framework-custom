package pe.axiz.initializr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.axiz.core.security.RateLimiter;
import tools.jackson.databind.json.JsonMapper;

/** Authenticated subject + operation quotas; generation concurrency is bounded in each replica. */
final class InitializrQuotaFilter extends OncePerRequestFilter {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final RateLimiter limiter;
  private final InitializrLimits limits;
  private final Semaphore workers;

  InitializrQuotaFilter(RateLimiter limiter, InitializrLimits limits) {
    this.limiter = limiter;
    this.limits = limits;
    this.workers = new Semaphore(limits.concurrency());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = operation(request);
    int maximum =
        switch (path) {
          case "/v1/projects/github" -> limits.publication();
          case "/v1/projects" -> limits.generation();
          case "/v1/projects/validate", "/v1/contracts/lint" -> limits.validation();
          default -> 0;
        };
    if (!"POST".equals(request.getMethod()) || maximum == 0) {
      chain.doFilter(request, response);
      return;
    }
    Authentication identity = SecurityContextHolder.getContext().getAuthentication();
    if (identity == null
        || !identity.isAuthenticated()
        || identity instanceof AnonymousAuthenticationToken
        || identity.getName() == null
        || identity.getName().isBlank()
        || identity.getName().length() > 1024) {
      problem(response, 401, "Authentication required");
      return;
    }
    RateLimiter.Decision decision;
    try {
      decision =
          limiter.tryAcquire(
              path + ":" + subject(identity.getName()), maximum, limits.window(), false);
    } catch (RuntimeException unavailable) {
      problem(response, 503, "Operation budget unavailable");
      return;
    }
    if (!decision.allowed()) {
      response.setHeader(
          "Retry-After", Long.toString(Math.max(1, decision.retryAfter().toSeconds())));
      problem(response, 429, "Operation budget exhausted");
      return;
    }
    if (!workers.tryAcquire()) {
      response.setHeader("Retry-After", "1");
      problem(response, 429, "Generation capacity exhausted");
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      workers.release();
    }
  }

  private static String operation(HttpServletRequest request) {
    // Use the same segment decoding as MVC; the quota key is always the canonical operation.
    for (String path :
        new String[] {
          "/v1/projects/github", "/v1/projects", "/v1/projects/validate", "/v1/contracts/lint"
        }) {
      if (PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path).matches(request))
        return path;
    }
    return "";
  }

  private static String subject(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Quota identity unavailable", unavailable);
    }
  }

  private static void problem(HttpServletResponse response, int status, String title)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    JSON.writeValue(response.getOutputStream(), Map.of("status", status, "title", title));
  }
}
