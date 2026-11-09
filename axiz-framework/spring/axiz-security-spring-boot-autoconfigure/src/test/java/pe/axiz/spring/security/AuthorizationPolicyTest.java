package pe.axiz.spring.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pe.axiz.core.security.AuthenticatedAccess;
import reactor.core.publisher.Mono;

class AuthorizationPolicyTest {
  static final String[] ISSUER = {
    "axiz.security.issuers[0].issuer=https://issuer.test",
    "axiz.security.issuers[0].jwk-set-uri=https://issuer.test/jwks",
    "axiz.security.issuers[0].audiences[0]=app"
  };

  private static WebApplicationContextRunner mvc(Class<?> controller) {
    return new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(ISSUER)
        .withUserConfiguration(controller)
        .withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
  }

  @RestController
  @PreAuthorize("not isRememberMe()")
  static class Open {
    @GetMapping("/policy")
    public String get() {
      return "ok";
    }
  }

  @RestController
  @PreAuthorize("permitAll()")
  static class True {
    @GetMapping("/policy")
    public String get() {
      return "ok";
    }
  }

  @RestController
  @PreAuthorize("not hasAuthority('admin')")
  static class Exclusion {
    @GetMapping("/policy")
    public String get() {
      return "ok";
    }
  }

  @RestController
  @PreAuthorize("@policy.check(#id) or true")
  static class DynamicTrue {
    @GetMapping("/policy")
    public String get() {
      return "ok";
    }
  }

  static Stream<Class<?>> permissive() {
    return Stream.of(Open.class, True.class, Exclusion.class, DynamicTrue.class);
  }

  @ParameterizedTest
  @MethodSource("permissive")
  void permissivePoliciesDoNotSatisfyCoverage(Class<?> controller) {
    mvc(controller)
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class,
                    () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext())));
  }

  static class Policy {
    public boolean check(Object id) {
      return true;
    }
  }

  @Test
  void startupNeverInvokesARealApplicationPolicyEvenWhenOrTrueMakesItTrivial() {
    Policy policy = org.mockito.Mockito.spy(new Policy());
    mvc(DynamicTrue.class)
        .withBean("policy", Policy.class, () -> policy)
        .run(
            ctx -> {
              assertThrows(
                  IllegalStateException.class,
                  () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext()));
              org.mockito.Mockito.verify(policy, org.mockito.Mockito.never())
                  .check(org.mockito.ArgumentMatchers.any());
            });
  }

  @Test
  void specificHttpRulesStillCoverPermissiveMethods() {
    mvc(Open.class)
        .withPropertyValues(
            "axiz.security.authorization.rules[0].paths[0]=/policy",
            "axiz.security.authorization.rules[0].authorities[0]=SCOPE_read")
        .run(
            ctx ->
                assertDoesNotThrow(
                    () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext())));
  }

  @RestController
  @AuthenticatedAccess
  static class Authenticated {
    @GetMapping("/policy")
    public String get() {
      return "ok";
    }
  }

  @RestController
  @AuthenticatedAccess
  @PreAuthorize("permitAll()")
  static class Conflicting {
    @GetMapping("/policy")
    public String get() {
      return "ok";
    }
  }

  @Test
  void authenticationIsExplicitAndEnforcedByTheRealAdvisor() {
    mvc(Authenticated.class)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              var controller = ctx.getBean(Authenticated.class);
              SecurityContextHolder.clearContext();
              assertThrows(
                  org.springframework.security.core.AuthenticationException.class, controller::get);
              for (var auth :
                  List.of(
                      jwt(),
                      new RememberMeAuthenticationToken(
                          "key", "user", List.of(new SimpleGrantedAuthority("ROLE_USER"))))) {
                SecurityContextHolder.getContext().setAuthentication(auth);
                try {
                  assertEquals("ok", controller.get());
                } finally {
                  SecurityContextHolder.clearContext();
                }
              }
            });
  }

  static JwtAuthenticationToken jwt() {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("token").header("alg", "RS256").subject("user").build(), List.of());
  }

  @Test
  void contradictoryDeclarationsFailEvenInWarnMode() {
    mvc(Conflicting.class)
        .withPropertyValues("axiz.security.authorization.unprotected=warn")
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class,
                    () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext())));
    mvc(Authenticated.class)
        .withPropertyValues("axiz.security.public-paths[0]=/policy")
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class,
                    () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext())));
  }

  @RestController
  @PreAuthorize("T(java.lang.Boolean).TRUE")
  static class Unsupported {
    @GetMapping("/policy")
    public String get() {
      return "ok";
    }
  }

  @Test
  void unsupportedSyntaxFailsEvenInWarnMode() {
    mvc(Unsupported.class)
        .withPropertyValues("axiz.security.authorization.unprotected=warn")
        .run(
            ctx ->
                assertThrows(
                    UnsupportedAuthorizationExpression.class,
                    () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext())));
  }

  @Retention(RetentionPolicy.RUNTIME)
  @PreAuthorize("hasAuthority('{value}')")
  @interface Permission {
    String value();
  }

  @RestController
  static class Templated {
    @GetMapping("/policy")
    @Permission("SCOPE_read")
    public String get() {
      return "ok";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Templates {
    @Bean
    static AnnotationTemplateExpressionDefaults templateDefaults() {
      return new AnnotationTemplateExpressionDefaults();
    }
  }

  @Test
  void templatesUseTheSameScannerAsSpringSecurity() {
    mvc(Templated.class)
        .withUserConfiguration(Templates.class)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              SecurityContextHolder.getContext().setAuthentication(jwt());
              try {
                assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    ctx.getBean(Templated.class)::get);
              } finally {
                SecurityContextHolder.clearContext();
              }
            });
  }

  @RestController
  @AuthenticatedAccess
  static class ReactiveAuthenticated {
    @GetMapping("/policy")
    public Mono<String> get() {
      return Mono.just("ok");
    }
  }

  @Test
  void reactiveAuthenticationUsesTheActualReactorContext() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(ISSUER)
        .withUserConfiguration(ReactiveAuthenticated.class)
        .withBean(
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                .class,
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                ::new)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              var controller = ctx.getBean(ReactiveAuthenticated.class);
              assertThrows(
                  org.springframework.security.access.AccessDeniedException.class,
                  () -> controller.get().block());
              assertEquals(
                  "ok",
                  controller
                      .get()
                      .contextWrite(
                          org.springframework.security.core.context.ReactiveSecurityContextHolder
                              .withAuthentication(jwt()))
                      .block());
            });
  }

  @Configuration(proxyBeanMethods = false)
  @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity(
      securedEnabled = true,
      jsr250Enabled = true)
  static class AdditionalFamilies {}

  @RestController
  static class Secured {
    @GetMapping("/policy")
    @org.springframework.security.access.annotation.Secured("IS_AUTHENTICATED_FULLY")
    public String get() {
      return "ok";
    }
  }

  @RestController
  static class Roles {
    @GetMapping("/policy")
    @jakarta.annotation.security.RolesAllowed("admin")
    public String get() {
      return "ok";
    }
  }

  @RestController
  static class Deny {
    @GetMapping("/policy")
    @jakarta.annotation.security.DenyAll
    public String get() {
      return "ok";
    }
  }

  @RestController
  static class Permit {
    @GetMapping("/policy")
    @jakarta.annotation.security.PermitAll
    public String get() {
      return "ok";
    }
  }

  @Test
  void securedAttributesAreLiteralAuthoritiesAndJsr250UsesItsOwnFamily() {
    mvc(Secured.class)
        .withUserConfiguration(AdditionalFamilies.class)
        .run(
            ctx -> {
              AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext());
              var controller = ctx.getBean(Secured.class);
              SecurityContextHolder.getContext().setAuthentication(jwt());
              try {
                assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    controller::get);
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new org.springframework.security.authentication.TestingAuthenticationToken(
                            "user", "secret", "IS_AUTHENTICATED_FULLY"));
                assertEquals("ok", controller.get());
              } finally {
                SecurityContextHolder.clearContext();
              }
            });
    for (Class<?> type : List.of(Roles.class, Deny.class))
      mvc(type)
          .withUserConfiguration(AdditionalFamilies.class)
          .run(
              ctx ->
                  assertDoesNotThrow(
                      () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext())));
    mvc(Permit.class)
        .withUserConfiguration(AdditionalFamilies.class)
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class,
                    () -> AuthorizationCoverageTest.ready(ctx.getSourceApplicationContext())));
  }
}
