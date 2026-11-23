package pe.axiz.spring.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pe.axiz.core.security.PublicEndpoint;
import pe.axiz.spring.errors.ProblemErrorController;
import pe.axiz.spring.errors.ProblemResponder;
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
            ProblemErrorController.class,
            () -> new ProblemErrorController(Mockito.mock(ProblemResponder.class)))
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
    final AtomicInteger calls = new AtomicInteger();

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('SCOPE_read') and hasAuthority('SCOPE_write')")
    public String pay() {
      calls.incrementAndGet();
      return "ok";
    }
  }

  @RestController
  static class ProtectedReactiveController {
    final AtomicInteger calls = new AtomicInteger();

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('SCOPE_read') and hasAuthority('SCOPE_write')")
    public Mono<String> pay() {
      calls.incrementAndGet();
      return Mono.just("ok");
    }
  }

  @RestController
  static class PublicController {
    @PostMapping("/payments")
    @PublicEndpoint
    public String pay() {
      return "ok";
    }
  }

  @RestController
  static class PostOnlyController {
    @PostMapping("/payments")
    @PostAuthorize("permitAll()")
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
              var auth = new TestingAuthenticationToken("user", "credentials", "SCOPE_read");
              SecurityContextHolder.getContext().setAuthentication(auth);
              try {
                assertThrows(AccessDeniedException.class, controller::pay);
                // Se inspecciona el contador del objetivo, ya que los campos de CGLIB pertenecen al
                // propio proxy.
                var target =
                    (ProtectedController) ((Advised) controller).getTargetSource().getTarget();
                assertEquals(0, target.calls.get());
              } finally {
                SecurityContextHolder.clearContext();
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
              var auth = new TestingAuthenticationToken("user", "credentials", "SCOPE_read");
              assertThrows(
                  AccessDeniedException.class,
                  () ->
                      controller
                          .pay()
                          .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                          .block());
              var target =
                  (ProtectedReactiveController)
                      ((Advised) controller).getTargetSource().getTarget();
              assertEquals(0, target.calls.get());
            });
  }

  @Test
  void annotationWithoutAnAdvisorAndPostAuthorizationAreInsufficient() throws Exception {
    mvc()
        .run(
            ctx -> {
              var target = new ProtectedController();
              var proxy = new ProxyFactory(target);
              proxy.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
              for (Object bean : List.of(target, proxy.getProxy())) {
                var endpoint =
                    new HttpAuthorizationCoverage.Endpoint(
                        new HandlerMethod(bean, ProtectedController.class.getMethod("pay")),
                        Set.of("/payments"),
                        Set.of("POST"));
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        HttpAuthorizationCoverage.verify(
                            ctx.getSourceApplicationContext(),
                            ctx.getBean(AxizSecurityProperties.class),
                            List.of(endpoint),
                            false,
                            true,
                            "/actuator"));
              }
            });
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
              var request = new MockHttpServletRequest("POST", "/payments");
              request.setServletPath("/payments");
              request.addHeader("Authorization", "Bearer token");
              var response = new MockHttpServletResponse();
              var calls = new AtomicInteger();
              ctx.getBean("springSecurityFilterChain", Filter.class)
                  .doFilter(request, response, (req, res) -> calls.incrementAndGet());
              assertEquals(403, response.getStatus());
              assertEquals(0, calls.get());
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class TestResolver {
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> resolver() {
      return request ->
          authentication -> new TestingAuthenticationToken("user", "credentials", "SCOPE_read");
    }
  }

  @RestController
  static class ReadController {
    @GetMapping("/read")
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
    Assertions.assertTrue(HttpAuthorizationCoverage.covers("/payments/**", "/payments/{id}"));
    Assertions.assertTrue(HttpAuthorizationCoverage.covers("/payments/**", "/payments"));
    Assertions.assertTrue(HttpAuthorizationCoverage.covers("/**", "/payments"));
    for (String pattern : List.of("/pay*/**", "/pay?/**", "/{area}/**", "/payments"))
      assertFalse(HttpAuthorizationCoverage.covers(pattern, "/payments/{id}"));
    assertFalse(HttpAuthorizationCoverage.covers("/payments/**", "/payments-other/{id}"));
  }

  @Test
  void invalidCoverageModesAndRulesFailBeforeRoutesAreAccepted() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizSecurityProperties.Authorization("typo", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizSecurityProperties.RouteRule(List.of(), List.of(), List.of("SCOPE_read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizSecurityProperties.RouteRule(List.of("/payments"), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(
                List.of("/payments"), List.of(), List.of("ROLE_ANONYMOUS")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(List.of("/**"), List.of(), List.of("SCOPE_read")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizSecurityProperties.RouteRule(
                List.of("/payments"), List.of("TYPO"), List.of("SCOPE_read")));
    mvc()
        .withBean(OpenController.class, OpenController::new)
        .withPropertyValues("axiz.security.authorization.unprotected=warn")
        .run(ctx -> assertDoesNotThrow(() -> ready(ctx.getSourceApplicationContext())));
  }

  @Configuration(proxyBeanMethods = false)
  static class NativeTestResolver {
    @Bean
    JwtIssuerReactiveAuthenticationManagerResolver nativeResolver() {
      var resolver = Mockito.mock(JwtIssuerReactiveAuthenticationManagerResolver.class);
      Mockito.when(resolver.resolve(ArgumentMatchers.any()))
          .thenReturn(
              Mono.just(
                  authentication ->
                      Mono.just(
                          new TestingAuthenticationToken("user", "credentials", "SCOPE_read"))));
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
                  MockServerWebExchange.from(
                      MockServerHttpRequest.post("/payments")
                          .header("Authorization", "Bearer token"));
              var invoked = new AtomicInteger();
              ctx.getBean(WebFilterChainProxy.class)
                  .filter(exchange, e -> Mono.fromRunnable(invoked::incrementAndGet))
                  .block();
              assertEquals(403, exchange.getResponse().getStatusCode().value());
              assertEquals(0, invoked.get());
            });
  }
}
