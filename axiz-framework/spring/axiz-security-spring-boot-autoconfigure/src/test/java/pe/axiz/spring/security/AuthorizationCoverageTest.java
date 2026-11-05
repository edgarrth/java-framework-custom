package pe.axiz.spring.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import reactor.core.publisher.Mono;

class AuthorizationCoverageTest {
  private static final String[] ISSUER = {
    "axiz.security.issuers[0].issuer=https://issuer.test",
    "axiz.security.issuers[0].jwk-set-uri=https://issuer.test/jwks",
    "axiz.security.issuers[0].audiences[0]=app"
  };

  @RestController
  static class OpenController {
    @PostMapping("/payments")
    public String pay() {
      return "ok";
    }
  }

  @RestController
  static class OpenReactiveController {
    @PostMapping("/payments")
    public Mono<String> pay() {
      return Mono.just("ok");
    }
  }

  static void ready(ConfigurableApplicationContext context) {
    context.publishEvent(
        new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
  }

  @Test
  void frameworkErrorRenderingDoesNotRequireBusinessScopes() {
    mvc()
        .withBean(
            pe.axiz.spring.errors.ProblemErrorController.class,
            () ->
                new pe.axiz.spring.errors.ProblemErrorController(
                    org.mockito.Mockito.mock(pe.axiz.spring.errors.ProblemResponder.class)))
        .run(ctx -> assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext())));
  }

  @Test
  void endpointsWithoutPriorAuthorizationFailByDefaultInBothStacks() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(ISSUER)
        .withBean(OpenController.class, OpenController::new)
        .withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class, () -> ready(ctx.getSourceApplicationContext())));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(ISSUER)
        .withBean(OpenReactiveController.class, OpenReactiveController::new)
        .withBean(
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                .class,
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                ::new)
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class, () -> ready(ctx.getSourceApplicationContext())));
  }

  @RestController
  static class ProtectedController {
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    @PostMapping("/payments")
    @org.springframework.security.access.prepost.PreAuthorize(
        "hasAuthority('SCOPE_read') and hasAuthority('SCOPE_write')")
    public String pay() {
      calls.incrementAndGet();
      return "ok";
    }
  }

  @RestController
  static class ProtectedReactiveController {
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    @PostMapping("/payments")
    @org.springframework.security.access.prepost.PreAuthorize(
        "hasAuthority('SCOPE_read') and hasAuthority('SCOPE_write')")
    public Mono<String> pay() {
      calls.incrementAndGet();
      return Mono.just("ok");
    }
  }

  @RestController
  static class PublicController {
    @PostMapping("/payments")
    @pe.axiz.core.security.PublicEndpoint
    public String pay() {
      return "ok";
    }
  }

  @RestController
  static class PostOnlyController {
    @PostMapping("/payments")
    @org.springframework.security.access.prepost.PostAuthorize("permitAll()")
    public String pay() {
      return "ok";
    }
  }

  private WebApplicationContextRunner mvc() {
    return new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(ISSUER)
        .withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new);
  }

  @Test
  void aRealMethodAdvisorPassesAndRejectsBeforeInvokingTheTarget() {
    mvc()
        .withBean(ProtectedController.class, ProtectedController::new)
        .run(
            ctx -> {
              assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext()));
              var controller = ctx.getBean(ProtectedController.class);
              var auth =
                  new org.springframework.security.authentication.TestingAuthenticationToken(
                      "user", "credentials", "SCOPE_read");
              org.springframework.security.core.context.SecurityContextHolder.getContext()
                  .setAuthentication(auth);
              try {
                assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    controller::pay);
                // Inspect the target's counter, since CGLIB fields themselves belong to the proxy.
                var target =
                    (ProtectedController)
                        ((org.springframework.aop.framework.Advised) controller)
                            .getTargetSource()
                            .getTarget();
                assertEquals(0, target.calls.get());
              } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
              }
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(ISSUER)
        .withBean(ProtectedReactiveController.class, ProtectedReactiveController::new)
        .withBean(
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                .class,
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                ::new)
        .run(
            ctx -> {
              assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext()));
              var controller = ctx.getBean(ProtectedReactiveController.class);
              var auth =
                  new org.springframework.security.authentication.TestingAuthenticationToken(
                      "user", "credentials", "SCOPE_read");
              assertThrows(
                  org.springframework.security.access.AccessDeniedException.class,
                  () ->
                      controller
                          .pay()
                          .contextWrite(
                              org.springframework.security.core.context
                                  .ReactiveSecurityContextHolder.withAuthentication(auth))
                          .block());
              var target =
                  (ProtectedReactiveController)
                      ((org.springframework.aop.framework.Advised) controller)
                          .getTargetSource()
                          .getTarget();
              assertEquals(0, target.calls.get());
            });
  }

  @Test
  void annotationWithoutAnAdvisorAndPostAuthorizationAreInsufficient() throws Exception {
    var target = new ProtectedController();
    var method = ProtectedController.class.getMethod("pay");
    assertFalse(
        HttpAuthorizationCoverage.priorAdvisor(
            target, new org.springframework.web.method.HandlerMethod(target, method), false));
    var proxy = new org.springframework.aop.framework.ProxyFactory(target);
    proxy.addAdvice(
        (org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
    Object bean = proxy.getProxy();
    assertFalse(
        HttpAuthorizationCoverage.priorAdvisor(
            bean, new org.springframework.web.method.HandlerMethod(bean, method), false));
    mvc()
        .withBean(PostOnlyController.class, PostOnlyController::new)
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class, () -> ready(ctx.getSourceApplicationContext())));
  }

  @Test
  void publicMarkerRequiresAnEffectivePermitAllRoute() {
    mvc()
        .withBean(PublicController.class, PublicController::new)
        .run(
            ctx ->
                assertThrows(
                    IllegalStateException.class, () -> ready(ctx.getSourceApplicationContext())));
    mvc()
        .withBean(PublicController.class, PublicController::new)
        .withPropertyValues("axiz.security.public-paths[0]=/payments")
        .run(ctx -> assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext())));
  }

  @Test
  void aSpecificHttpRuleIsAppliedAndRequiresAllAuthorities() {
    mvc()
        .withBean(OpenController.class, OpenController::new)
        .withPropertyValues(
            "axiz.security.authorization.rules[0].paths[0]=/payments",
            "axiz.security.authorization.rules[0].methods[0]=POST",
            "axiz.security.authorization.rules[0].authorities[0]=SCOPE_read",
            "axiz.security.authorization.rules[0].authorities[1]=SCOPE_write")
        .withUserConfiguration(TestResolver.class)
        .run(
            ctx -> {
              assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext()));
              var request =
                  new org.springframework.mock.web.MockHttpServletRequest("POST", "/payments");
              request.setServletPath("/payments");
              request.addHeader("Authorization", "Bearer token");
              var response = new org.springframework.mock.web.MockHttpServletResponse();
              var calls = new java.util.concurrent.atomic.AtomicInteger();
              ctx.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)
                  .doFilter(request, response, (req, res) -> calls.incrementAndGet());
              assertEquals(403, response.getStatus());
              assertEquals(0, calls.get());
            });
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class TestResolver {
    @org.springframework.context.annotation.Bean
    org.springframework.security.authentication.AuthenticationManagerResolver<
            jakarta.servlet.http.HttpServletRequest>
        resolver() {
      return request ->
          authentication ->
              new org.springframework.security.authentication.TestingAuthenticationToken(
                  "user", "credentials", "SCOPE_read");
    }
  }

  @RestController
  static class ReadController {
    @org.springframework.web.bind.annotation.GetMapping("/read")
    public String read() {
      return "ok";
    }
  }

  @Test
  void getCoverageMustAlsoProtectTheImplicitHeadMapping() {
    var runner =
        mvc()
            .withBean(ReadController.class, ReadController::new)
            .withPropertyValues(
                "axiz.security.authorization.rules[0].paths[0]=/read",
                "axiz.security.authorization.rules[0].methods[0]=GET",
                "axiz.security.authorization.rules[0].authorities[0]=SCOPE_read");
    runner.run(
        ctx ->
            assertThrows(
                IllegalStateException.class, () -> ready(ctx.getSourceApplicationContext())));
    runner
        .withPropertyValues("axiz.security.authorization.rules[0].methods[1]=HEAD")
        .run(ctx -> assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext())));
  }

  @Test
  void recursiveRouteCoverageCannotAcceptIntersectingOrDynamicPrefixes() {
    org.junit.jupiter.api.Assertions.assertTrue(
        HttpAuthorizationCoverage.covers("/payments/**", "/payments/{id}"));
    org.junit.jupiter.api.Assertions.assertTrue(
        HttpAuthorizationCoverage.covers("/payments/**", "/payments"));
    org.junit.jupiter.api.Assertions.assertTrue(
        HttpAuthorizationCoverage.covers("/**", "/payments"));
    for (String pattern : java.util.List.of("/pay*/**", "/pay?/**", "/{area}/**", "/payments"))
      assertFalse(HttpAuthorizationCoverage.covers(pattern, "/payments/{id}"));
    assertFalse(HttpAuthorizationCoverage.covers("/payments/**", "/payments-other/{id}"));
  }

  @Test
  void invalidCoverageModesAndRulesFailBeforeRoutesAreAccepted() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizSecurityProperties.Authorization("typo", java.util.List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(
                java.util.List.of(), java.util.List.of(), java.util.List.of("SCOPE_read")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(
                java.util.List.of("/payments"), java.util.List.of(), java.util.List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(
                java.util.List.of("/payments"),
                java.util.List.of(),
                java.util.List.of("ROLE_ANONYMOUS")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(
                java.util.List.of("/**"), java.util.List.of(), java.util.List.of("SCOPE_read")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(
                java.util.List.of("/payments"),
                java.util.List.of("TYPO"),
                java.util.List.of("SCOPE_read")));
    mvc()
        .withBean(OpenController.class, OpenController::new)
        .withPropertyValues("axiz.security.authorization.unprotected=warn")
        .run(ctx -> assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext())));
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class NativeTestResolver {
    @org.springframework.context.annotation.Bean
    org.springframework.security.oauth2.server.resource.authentication
            .JwtIssuerReactiveAuthenticationManagerResolver
        nativeResolver() {
      var resolver =
          org.mockito.Mockito.mock(
              org.springframework.security.oauth2.server.resource.authentication
                  .JwtIssuerReactiveAuthenticationManagerResolver.class);
      org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
          .thenReturn(
              Mono.just(
                  authentication ->
                      Mono.just(
                          new org.springframework.security.authentication
                              .TestingAuthenticationToken("user", "credentials", "SCOPE_read"))));
      return resolver;
    }
  }

  @Test
  void specificNativeHttpRulesAreEffectiveAndDenyAPartialScopeBeforeInvocation() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withPropertyValues(ISSUER)
        .withBean(OpenReactiveController.class, OpenReactiveController::new)
        .withBean(
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                .class,
            org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
                ::new)
        .withPropertyValues(
            "axiz.security.authorization.rules[0].paths[0]=/payments",
            "axiz.security.authorization.rules[0].methods[0]=POST",
            "axiz.security.authorization.rules[0].authorities[0]=SCOPE_read",
            "axiz.security.authorization.rules[0].authorities[1]=SCOPE_write")
        .withUserConfiguration(NativeTestResolver.class)
        .run(
            ctx -> {
              assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext()));
              var exchange =
                  org.springframework.mock.web.server.MockServerWebExchange.from(
                      org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(
                              "/payments")
                          .header("Authorization", "Bearer token"));
              var invoked = new java.util.concurrent.atomic.AtomicInteger();
              ctx.getBean(org.springframework.security.web.server.WebFilterChainProxy.class)
                  .filter(exchange, e -> Mono.fromRunnable(invoked::incrementAndGet))
                  .block();
              assertEquals(403, exchange.getResponse().getStatusCode().value());
              assertEquals(0, invoked.get());
            });
  }
}
