package pe.axiz.spring.tokenization.vault.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies transport/no-store rules before authentication, including errors and unmapped methods.
 */
final class ServletVaultGuard extends OncePerRequestFilter implements Ordered {
  private final VaultHttpPolicy policy;

  ServletVaultGuard(VaultHttpPolicy policy) {
    this.policy = policy;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !policy.protectedPath(
        request.getRequestURI().substring(request.getContextPath().length()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var guarded = new NoStoreResponse(response);
    try {
      policy.transport(request.isSecure());
    } catch (RuntimeException failure) {
      try (var reply = VaultHttpReply.error(failure)) {
        ServletVaultController.write(guarded, reply);
      }
      return;
    }
    chain.doFilter(request, guarded);
  }

  private static final class NoStoreResponse extends HttpServletResponseWrapper {
    NoStoreResponse(HttpServletResponse response) {
      super(response);
      protect();
    }

    private void protect() {
      super.setHeader("Cache-Control", "no-store");
      super.setHeader("Pragma", "no-cache");
      super.setHeader("Expires", "0");
      super.setHeader("X-Content-Type-Options", "nosniff");
    }

    @Override
    public void reset() {
      super.reset();
      protect();
    }

    @Override
    public void setHeader(String name, @Nullable String value) {
      if ("Cache-Control".equalsIgnoreCase(name)) super.setHeader(name, "no-store");
      else super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, @Nullable String value) {
      if ("Cache-Control".equalsIgnoreCase(name)) super.setHeader(name, "no-store");
      else super.addHeader(name, value);
    }
  }
}
