package pe.axiz.initializr.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.core.security.InMemoryRateLimiter;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.config.SecurityConfiguration;
import pe.axiz.initializr.service.GenerationService;
import pe.axiz.initializr.service.GitHubPublisher;

/**
 * Inicio de sesión solo mediante el SSO y la autorización de GitHub como respuesta de la API, no
 * como redirección.
 */
@WebMvcTest(
    controllers = GitHubController.class,
    properties = {"axiz.config.environment=test", "AXIZ_SSO_ISSUER=https://sso.example.test"})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, GitHubControllerTest.Beans.class})
class GitHubControllerTest {

  @TestConfiguration
  @EnableConfigurationProperties(InitializrProperties.class)
  static class Beans {
    @Bean
    RateLimiter limiter() {
      return new InMemoryRateLimiter(Clock.systemUTC());
    }

    @Bean
    GenerationService generationService() {
      return new GenerationService(new SimpleMeterRegistry());
    }

    @Bean
    ClientRegistrationRepository clientRegistrations() {
      ClientRegistration sso =
          ClientRegistration.withRegistrationId(SecurityConfiguration.SSO)
              .clientId("axiz-initializr")
              .clientSecret("test-only")
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
              .scope("openid", "profile")
              .authorizationUri("https://sso.example.test/auth")
              .tokenUri("https://sso.example.test/token")
              .jwkSetUri("https://sso.example.test/certs")
              .userNameAttributeName("sub")
              .build();
      ClientRegistration github =
          CommonOAuth2Provider.GITHUB
              .getBuilder(SecurityConfiguration.GITHUB)
              .clientId("github-client")
              .clientSecret("test-only")
              .redirectUri("{baseUrl}/authorized/{registrationId}")
              .build();
      return new InMemoryClientRegistrationRepository(sso, github);
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean GitHubPublisher publisher;

  @Test
  void theBrowserGoesStraightToTheSso() throws Exception {
    String location =
        mvc.perform(get("/").accept(MediaType.TEXT_HTML))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    // relativa (mismo sitio) o absoluta en este mismo host: ambas son válidas; otro host no
    URI target = URI.create(String.valueOf(location));
    assertThat(target.getPath()).isEqualTo("/oauth2/authorization/sso");
    assertThat(target.getHost()).isIn(null, "localhost");
  }

  @Test
  void gitHubAuthorizationUsesItsOwnCallback() throws Exception {
    String location =
        mvc.perform(get("/oauth2/authorization/github"))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    assertThat(location)
        .startsWith("https://github.com/login/oauth/authorize")
        .contains("redirect_uri=http://localhost/authorized/github");
  }

  @Test
  void missingGitHubAuthorizationIsAProblemNotARedirect() throws Exception {
    mvc.perform(
            post("/v1/projects/github")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"orders\",\"groupId\":\"pe.axiz.orders\","
                        + "\"basePackage\":\"pe.axiz.orders\",\"archetype\":\"business\"}"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.registration").value("github"))
        .andExpect(jsonPath("$.authorize").value("/oauth2/authorization/github"));
  }

  @Test
  void invalidOperationHeaderAndLongDescriptionAreFieldErrors() throws Exception {
    String body =
        "{\"name\":\"orders\",\"groupId\":\"pe.axiz.orders\","
            + "\"basePackage\":\"pe.axiz.orders\",\"archetype\":\"business\"}";
    mvc.perform(
            post("/v1/projects/github")
                .with(jwt().jwt(j -> j.subject("missing-header")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/v1/projects/github")
                .with(jwt().jwt(j -> j.subject("invalid-header")))
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/v1/projects/github")
                .with(jwt().jwt(j -> j.subject("long-description")))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body.substring(0, body.length() - 1)
                        + ",\"description\":\""
                        + "x".repeat(161)
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("description"));
    org.mockito.Mockito.verifyNoInteractions(publisher);
  }

  @Test
  void validationFailureKeepsTheSessionGrantForACorrectedRequest() throws Exception {
    var identity =
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated("preserved-grant", "unused", java.util.List.of());
    var sessionRequest = new org.springframework.mock.web.MockHttpServletRequest();
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    var clients = new pe.axiz.initializr.config.EphemeralAuthorizedClients(Clock.systemUTC());
    var registration =
        CommonOAuth2Provider.GITHUB
            .getBuilder("github")
            .clientId("test-client")
            .clientSecret("test-only")
            .build();
    var now = java.time.Instant.now();
    var grant =
        new org.springframework.security.oauth2.client.OAuth2AuthorizedClient(
            registration,
            identity.getName(),
            new org.springframework.security.oauth2.core.OAuth2AccessToken(
                org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER,
                "test-only-token",
                now,
                now.plusSeconds(3600)));
    clients.saveAuthorizedClient(grant, identity, sessionRequest, response);
    var session = (org.springframework.mock.web.MockHttpSession) sessionRequest.getSession();
    mvc.perform(
            post("/v1/projects/github")
                .session(session)
                .with(jwt().jwt(j -> j.subject(identity.getName())))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"orders\",\"groupId\":\"pe.axiz.orders\","
                        + "\"basePackage\":\"pe.axiz.orders\",\"archetype\":\"business\",\"description\":\""
                        + "x".repeat(161)
                        + "\"}"))
        .andExpect(status().isBadRequest());
    assertThat(clients.consume(identity, sessionRequest, response)).isSameAs(grant);
    org.mockito.Mockito.verifyNoInteractions(publisher);
  }

  @Test
  void gitHubCallbackLandsBackOnTheUi() throws Exception {
    mvc.perform(get("/authorized/github").with(jwt()))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", "/?github=authorized"));
  }
}
