package pe.axiz.initializr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

class EphemeralAuthorizedClientsTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private final Authentication user = identity("corporate-user");
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final EphemeralAuthorizedClients clients = at(NOW);

  @Test
  void authorizationIsConsumedOnceAndIsNotSharedWithAnotherSession() {
    OAuth2AuthorizedClient client = client("github", user, NOW.plusSeconds(3600));
    clients.saveAuthorizedClient(client, user, request, response);
    assertThat(
            (OAuth2AuthorizedClient)
                clients.loadAuthorizedClient("github", user, new MockHttpServletRequest()))
        .isNull();
    assertThat(clients.consume(user, request, response)).isSameAs(client);
    assertThat(clients.consume(user, request, response)).isNull();
  }

  @Test
  void absoluteLifetimeIsNotExtendedByReadingOrConstructingAnotherRepository() {
    clients.saveAuthorizedClient(
        client("github", user, NOW.plusSeconds(3600)), user, request, response);
    assertThat(
            (OAuth2AuthorizedClient)
                at(NOW.plusSeconds(299)).loadAuthorizedClient("github", user, request))
        .isNotNull();
    assertThat(
            (OAuth2AuthorizedClient)
                at(NOW.plus(Duration.ofMinutes(5))).loadAuthorizedClient("github", user, request))
        .isNull();
    assertThat(clients.consume(user, request, response)).isNull();
  }

  @Test
  void providerExpiryWinsOverTheSessionDeadline() {
    clients.saveAuthorizedClient(
        client("github", user, NOW.plusSeconds(30)), user, request, response);
    assertThat(at(NOW.plusSeconds(30)).consume(user, request, response)).isNull();
  }

  @Test
  void neverRetainsSsoTokensAndRefusesPrincipalChanges() {
    clients.saveAuthorizedClient(
        client("sso", user, NOW.plusSeconds(3600)), user, request, response);
    assertThat(request.getSession(false)).isNull();
    clients.saveAuthorizedClient(
        client("github", user, NOW.plusSeconds(3600)), user, request, response);
    assertThat(clients.consume(identity("other-user"), request, response)).isNull();
    assertThat(clients.consume(user, request, response)).isNull();
    assertThatThrownBy(
            () ->
                clients.saveAuthorizedClient(
                    client("github", user, NOW.plusSeconds(3600)),
                    identity("other-user"),
                    request,
                    response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidatingTheLoginSessionRemovesTheGrant() {
    clients.saveAuthorizedClient(
        client("github", user, NOW.plusSeconds(3600)), user, request, response);
    request.getSession().invalidate();
    assertThat(clients.consume(user, request, response)).isNull();
  }

  @Test
  void concurrentRequestsUsingDifferentSessionFacadesConsumeOnlyOnce() throws Exception {
    var session = new org.springframework.mock.web.MockHttpSession();
    new org.springframework.web.util.HttpSessionMutexListener()
        .sessionCreated(new jakarta.servlet.http.HttpSessionEvent(session));
    request.setSession(session);
    clients.saveAuthorizedClient(
        client("github", user, NOW.plusSeconds(3600)), user, request, response);
    var start = new java.util.concurrent.CountDownLatch(1);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var work = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 12; i++) {
        var facade =
            org.mockito.Mockito.mock(
                jakarta.servlet.http.HttpSession.class,
                org.mockito.AdditionalAnswers.delegatesTo(session));
        var caller = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(caller.getSession(false)).thenReturn(facade);
        work.add(
            pool.submit(
                () -> {
                  start.await();
                  if (clients.consume(user, caller, new MockHttpServletResponse()) != null)
                    count.incrementAndGet();
                  return null;
                }));
      }
      start.countDown();
      for (var future : work) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(count).hasValue(1);
  }

  private static Authentication identity(String name) {
    return new UsernamePasswordAuthenticationToken(name, "unused", List.of());
  }

  private static EphemeralAuthorizedClients at(Instant instant) {
    return new EphemeralAuthorizedClients(Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static OAuth2AuthorizedClient client(
      String registration, Authentication principal, Instant expires) {
    return new OAuth2AuthorizedClient(
        CommonOAuth2Provider.GITHUB
            .getBuilder(registration)
            .clientId("test-client")
            .clientSecret("test-only")
            .build(),
        principal.getName(),
        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-only-token", NOW, expires));
  }
}
