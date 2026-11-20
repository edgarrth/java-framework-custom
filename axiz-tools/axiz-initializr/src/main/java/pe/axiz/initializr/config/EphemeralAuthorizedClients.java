package pe.axiz.initializr.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.util.WebUtils;

/** Session-bound GitHub authorization, with an absolute five-minute lifetime and removal on use. */
public final class EphemeralAuthorizedClients implements OAuth2AuthorizedClientRepository {
  private static final String DEADLINE = EphemeralAuthorizedClients.class.getName() + ".deadline";
  private static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final String CLIENT = EphemeralAuthorizedClients.class.getName() + ".client";
  private final Clock clock;

  public EphemeralAuthorizedClients(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  // The generic return-only signature is imposed by Spring Security's repository contract.
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  @Override
  public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
      String registration, Authentication principal, HttpServletRequest request) {
    if (!SecurityConfiguration.GITHUB.equals(registration)) return null;
    HttpSession session = request.getSession(false);
    if (session == null) return null;
    synchronized (WebUtils.getSessionMutex(session)) {
      Object stored = session.getAttribute(CLIENT);
      OAuth2AuthorizedClient client = stored instanceof OAuth2AuthorizedClient value ? value : null;
      Object deadline = session.getAttribute(DEADLINE);
      if (client == null) return null;
      Instant expires = client.getAccessToken().getExpiresAt();
      if (principal instanceof AnonymousAuthenticationToken
          || !principal.isAuthenticated()
          || !principal.getName().equals(client.getPrincipalName())
          || !(deadline instanceof Instant end)
          || !end.isAfter(clock.instant())
          || (expires != null && !expires.isAfter(clock.instant()))) {
        session.removeAttribute(CLIENT);
        session.removeAttribute(DEADLINE);
        return null;
      }
      return (T) client;
    }
  }

  @Override
  public void saveAuthorizedClient(
      OAuth2AuthorizedClient client,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (!SecurityConfiguration.GITHUB.equals(client.getClientRegistration().getRegistrationId()))
      return;
    if (principal instanceof AnonymousAuthenticationToken
        || !principal.isAuthenticated()
        || !principal.getName().equals(client.getPrincipalName()))
      throw new IllegalArgumentException("GitHub authorization principal mismatch");
    HttpSession session = request.getSession();
    synchronized (WebUtils.getSessionMutex(session)) {
      session.setAttribute(CLIENT, client);
      session.setAttribute(DEADLINE, clock.instant().plus(LIFETIME));
    }
  }

  @Override
  public void removeAuthorizedClient(
      String registration,
      Authentication principal,
      HttpServletRequest request,
      @Nullable HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session == null) return;
    synchronized (WebUtils.getSessionMutex(session)) {
      if (!SecurityConfiguration.GITHUB.equals(registration)) return;
      session.removeAttribute(CLIENT);
      session.removeAttribute(DEADLINE);
    }
  }

  /**
   * Takes the authorization out of the session after local validation and before any remote
   * publication.
   */
  public @Nullable OAuth2AuthorizedClient consume(
      Authentication principal, HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session == null) return null;
    synchronized (WebUtils.getSessionMutex(session)) {
      OAuth2AuthorizedClient client =
          loadAuthorizedClient(SecurityConfiguration.GITHUB, principal, request);
      removeAuthorizedClient(SecurityConfiguration.GITHUB, principal, request, response);
      return client;
    }
  }
}
