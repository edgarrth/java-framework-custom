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
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.config.SecurityConfiguration;
import pe.axiz.initializr.service.GenerationService;
import pe.axiz.initializr.service.GitHubPublisher;

/** Sign-in only through the SSO, and the GitHub authorization as an API answer, not a redirect. */
@WebMvcTest(GitHubController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, GitHubControllerTest.Beans.class})
class GitHubControllerTest {

  @TestConfiguration
  @EnableConfigurationProperties(InitializrProperties.class)
  static class Beans {
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
    // relative (same site) or absolute on this same host: both are valid, another host is not
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
  void gitHubCallbackLandsBackOnTheUi() throws Exception {
    mvc.perform(get("/authorized/github").with(jwt()))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", "/?github=authorized"));
  }
}
