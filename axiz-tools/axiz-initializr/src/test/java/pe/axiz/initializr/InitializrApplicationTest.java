package pe.axiz.initializr;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.initializr.config.SecurityConfiguration;

/**
 * La aplicación completa arranca tal como se distribuye (todos los starters de axiz, los servicios
 * reales y la cadena de seguridad). El descubrimiento del SSO y la frontera de cuotas en Redis se
 * reemplazan por beans de prueba; el decodificador del resource server resuelve el emisor de forma
 * diferida y aquí nunca se invoca.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "AXIZ_SSO_ISSUER=https://sso.example.test",
      "axiz.security.rate-limit.enabled=false",
      "axiz.initializr.publication-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@AutoConfigureMockMvc
class InitializrApplicationTest {

  @TestConfiguration
  static class Sso {
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
  @MockitoBean RateLimiter limiter;

  @Test
  void startsAndServesTheCatalogOnlyToAuthenticatedCallers() throws Exception {
    mvc.perform(get("/v1/metadata")).andExpect(status().isUnauthorized());
    mvc.perform(get("/v1/metadata").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archetypes").isNotEmpty());
  }
}
