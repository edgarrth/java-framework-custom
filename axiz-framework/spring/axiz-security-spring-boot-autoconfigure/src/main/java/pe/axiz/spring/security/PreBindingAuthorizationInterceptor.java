package pe.axiz.spring.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Servlet side of {@link PreBindingAuthorization}: runs before argument resolution. */
final class PreBindingAuthorizationInterceptor implements HandlerInterceptor {

  private final PreAuthorizeAuthorizationManager manager;
  private final PreBindingAuthorization policy = new PreBindingAuthorization();

  PreBindingAuthorizationInterceptor(PreAuthorizeAuthorizationManager manager) {
    this.manager = manager;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method) || !policy.appliesTo(method)) return true;
    AuthorizationResult result =
        manager.authorize(
            () -> SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication(),
            PreBindingAuthorization.invocation(method));
    if (result != null && !result.isGranted())
      throw new AuthorizationDeniedException("Access Denied", result);
    return true;
  }
}
