package pe.axiz.spring.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.spring.errors.AxizErrorsAutoConfiguration;
import reactor.core.publisher.Mono;

/**
 * Las categorías de cobertura se comprueban contra el gestor de expresiones real, en ambas pilas
 * HTTP.
 */
class AuthorizationPredicateHttpTest {
  static class Endpoint {
    @GetMapping("/policy")
    public Mono<String> get() {
      return Mono.just("ok");
    }
  }

  @RestController
  @PreAuthorize("isFullyAuthenticated()")
  static class Fully extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("not isFullyAuthenticated()")
  static class NotFully extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("isRememberMe()")
  static class Remembered extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("not isRememberMe()")
  static class NotRemembered extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("isAnonymous()")
  static class Anonymous extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("not isAnonymous()")
  static class NotAnonymous extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("isAuthenticated()")
  static class Authenticated extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("not isAuthenticated()")
  static class NotAuthenticated extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("not hasAuthority('admin')")
  static class Exclusion extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  @RestController
  @PreAuthorize("hasAuthority('payments:write')")
  static class Restricted extends Endpoint {
    @Override
    @GetMapping("/policy")
    public Mono<String> get() {
      return super.get();
    }
  }

  static Stream<Arguments> predicates() {
    return Stream.of(
        Arguments.of(Fully.class, 200, 403), Arguments.of(NotFully.class, 403, 200),
        Arguments.of(Remembered.class, 403, 200), Arguments.of(NotRemembered.class, 200, 403),
        Arguments.of(Anonymous.class, 403, 403), Arguments.of(NotAnonymous.class, 200, 200),
        Arguments.of(Authenticated.class, 200, 200), Arguments.of(NotAuthenticated.class, 403, 403),
        Arguments.of(Exclusion.class, 200, 200), Arguments.of(Restricted.class, 403, 403));
  }

  @ParameterizedTest
  @MethodSource("predicates")
  void servletPredicatesMatchTheirDeclaredMeaning(
      Class<?> endpoint, int jwtStatus, int rememberedStatus) {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizErrorsAutoConfiguration.class, AxizSecurityAutoConfiguration.class))
        .withPropertyValues(AuthorizationPolicyTest.ISSUER)
        .withPropertyValues("axiz.security.authorization.unprotected=warn")
        .withUserConfiguration(AuthenticatedAccessHttpTest.Servlet.class, endpoint)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              var client = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
              assertEquals(
                  jwtStatus,
                  client
                      .perform(get("/policy").with(authentication(AuthorizationPolicyTest.jwt())))
                      .andReturn()
                      .getResponse()
                      .getStatus());
              assertEquals(
                  rememberedStatus,
                  client
                      .perform(
                          get("/policy")
                              .with(
                                  authentication(
                                      new RememberMeAuthenticationToken(
                                          "key",
                                          "user",
                                          List.of(new SimpleGrantedAuthority("ROLE_USER"))))))
                      .andReturn()
                      .getResponse()
                      .getStatus());
            });
  }

  @ParameterizedTest
  @MethodSource("predicates")
  void reactivePredicatesMatchTheirDeclaredMeaning(
      Class<?> endpoint, int jwtStatus, int rememberedStatus) {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizErrorsAutoConfiguration.class, AxizSecurityAutoConfiguration.class))
        .withPropertyValues(AuthorizationPolicyTest.ISSUER)
        .withPropertyValues("axiz.security.authorization.unprotected=warn")
        .withUserConfiguration(AuthenticatedAccessHttpTest.Reactive.class, endpoint)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              var client =
                  WebTestClient.bindToApplicationContext(ctx)
                      .apply(SecurityMockServerConfigurers.springSecurity())
                      .configureClient()
                      .build();
              client
                  .mutateWith(mockAuthentication(AuthorizationPolicyTest.jwt()))
                  .get()
                  .uri("/policy")
                  .exchange()
                  .expectStatus()
                  .isEqualTo(jwtStatus);
              client
                  .mutateWith(
                      mockAuthentication(
                          new RememberMeAuthenticationToken(
                              "key", "user", List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                  .get()
                  .uri("/policy")
                  .exchange()
                  .expectStatus()
                  .isEqualTo(rememberedStatus);
            });
  }
}
