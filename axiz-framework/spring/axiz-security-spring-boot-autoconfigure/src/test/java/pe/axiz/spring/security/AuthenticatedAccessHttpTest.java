package pe.axiz.spring.security;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import pe.axiz.spring.errors.AxizErrorsAutoConfiguration;

/** Real chains, method advisors and error handlers; no target invocation through a bare mock. */
class AuthenticatedAccessHttpTest {
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class Servlet {
    @Bean
    SecurityFilterChain permitHttp(HttpSecurity http) throws Exception {
      return http.csrf(c -> c.disable())
          .authorizeHttpRequests(a -> a.anyRequest().permitAll())
          .build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebFlux
  static class Reactive {
    @Bean
    SecurityWebFilterChain permitHttp(ServerHttpSecurity http) {
      return http.csrf(c -> c.disable())
          .authorizeExchange(a -> a.anyExchange().permitAll())
          .build();
    }
  }

  @Test
  void servletMethodContractSurvivesPermitAllAndErrorAdvice() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizErrorsAutoConfiguration.class, AxizSecurityAutoConfiguration.class))
        .withPropertyValues(AuthorizationPolicyTest.ISSUER)
        .withUserConfiguration(Servlet.class, AuthorizationPolicyTest.Authenticated.class)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              var client = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
              client.perform(get("/policy")).andExpect(status().isUnauthorized());
              client
                  .perform(get("/policy").with(authentication(AuthorizationPolicyTest.jwt())))
                  .andExpect(status().isOk());
              var remembered =
                  new RememberMeAuthenticationToken(
                      "key", "user", List.of(new SimpleGrantedAuthority("ROLE_USER")));
              client
                  .perform(get("/policy").with(authentication(remembered)))
                  .andExpect(status().isOk());
            });
  }

  @Test
  void reactiveMethodContractSurvivesPermitAllAndErrorHandler() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizErrorsAutoConfiguration.class, AxizSecurityAutoConfiguration.class))
        .withPropertyValues(AuthorizationPolicyTest.ISSUER)
        .withUserConfiguration(Reactive.class, AuthorizationPolicyTest.ReactiveAuthenticated.class)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              var client =
                  WebTestClient.bindToApplicationContext(ctx)
                      .apply(
                          org.springframework.security.test.web.reactive.server
                              .SecurityMockServerConfigurers.springSecurity())
                      .configureClient()
                      .build();
              client.get().uri("/policy").exchange().expectStatus().isUnauthorized();
              client
                  .mutateWith(mockAuthentication(AuthorizationPolicyTest.jwt()))
                  .get()
                  .uri("/policy")
                  .exchange()
                  .expectStatus()
                  .isOk();
            });
  }
}
