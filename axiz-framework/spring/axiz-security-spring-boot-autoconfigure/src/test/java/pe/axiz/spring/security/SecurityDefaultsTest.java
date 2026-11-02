package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.security.ClaimMapping;
import pe.axiz.core.security.ClaimsMapper;
import pe.axiz.core.security.RateLimiter;
import reactor.core.publisher.Mono;

/** What is reachable without a token, and how the filters behave at the edges. */
class SecurityDefaultsTest {

  private static final String ISSUER = "https://idp.example.com/realms/axiz";

  private static final String[] ISSUER_PROPERTIES = {
    "axiz.security.issuers[0].issuer=" + ISSUER,
    "axiz.security.issuers[0].jwk-set-uri=https://idp.example.com/realms/axiz/certs",
    "axiz.security.issuers[0].audiences[0]=payments-api"
  };

  private final WebApplicationContextRunner servlet =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
          .withPropertyValues(ISSUER_PROPERTIES);

  private final ReactiveWebApplicationContextRunner reactive =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
          .withPropertyValues(ISSUER_PROPERTIES);

  @Test
  void claimBindingUsesSafeDefaultsAndRejectsLegacyUsernameHeuristics() {
    servlet.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          var claims = ctx.getBean(AxizSecurityProperties.class).claims();
          assertFalse(claims.subjectAsClient());
          assertEquals(List.of("idtyp=app"), claims.machine());
        });
    reactive
        .withPropertyValues("axiz.security.claims.machine[0]=token_kind=service")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var principal =
                  ctx.getBean(ClaimsMapper.class)
                      .map(
                          java.util.Map.of(
                              "iss",
                              ISSUER,
                              "sub",
                              "technical-user",
                              "azp",
                              "service",
                              "token_kind",
                              "service"));
              assertTrue(principal.isMachine());
            });
    servlet
        .withPropertyValues("axiz.security.claims.machine[0]=preferred_username^=service-account-")
        .run(ctx -> assertThat(ctx).hasFailed());
    reactive
        .withPropertyValues(
            "axiz.security.claims.machine[0]=preferred_username=service-account-app")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  /** Status of a request through the servlet chain; 0 when it reached the application. */
  private static int call(Filter chain, String path, String bearer) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    if (bearer != null) {
      request.addHeader("Authorization", "Bearer " + bearer);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain app = new MockFilterChain();
    chain.doFilter(request, response, app);
    return app.getRequest() != null ? 0 : response.getStatus();
  }

  private static String unsignedToken(String issuer) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        b64.encodeToString(("{\"iss\":\"" + issuer + "\"}").getBytes(StandardCharsets.UTF_8));
    return header + "." + claims + ".";
  }

  @Test
  void onlyTheHealthProbesAreAnonymousByDefault() {
    servlet.run(
        ctx -> {
          Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
          assertEquals(0, call(chain, "/actuator/health", null));
          assertEquals(0, call(chain, "/actuator/health/readiness", null));
          assertEquals(401, call(chain, "/actuator/info", null));
          assertEquals(401, call(chain, "/v3/api-docs", null));
          assertEquals(401, call(chain, "/swagger-ui/index.html", null));
          assertEquals(401, call(chain, "/payments", null));
          assertEquals(401, call(chain, "/actuator/prometheus", null));
        });
  }

  @Test
  void documentationAndInfoOpenOnlyWhenAsked() {
    servlet
        .withPropertyValues(
            "axiz.security.public-api-docs=true",
            "axiz.security.public-info=true",
            "axiz.security.public-paths[0]=/catalog/**")
        .run(
            ctx -> {
              Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
              assertEquals(0, call(chain, "/v3/api-docs", null));
              assertEquals(0, call(chain, "/swagger-ui/index.html", null));
              assertEquals(0, call(chain, "/actuator/info", null));
              assertEquals(0, call(chain, "/catalog/banks", null));
              assertEquals(401, call(chain, "/actuator/env", null));
            });
  }

  @Test
  void aTokenFromAnUntrustedIssuerIsRejectedBeforeAnyKeyLookup() {
    servlet.run(
        ctx -> {
          Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
          assertEquals(401, call(chain, "/payments", unsignedToken("https://evil.example.com")));
        });
    assertThrows(
        InvalidBearerTokenException.class,
        () -> AxizSecurityAutoConfiguration.UNTRUSTED_ISSUER.authenticate(null));
  }

  @Test
  void theRateLimitFilterIsAddedOnlyWhenEnabled() {
    servlet
        .withPropertyValues(
            "axiz.security.rate-limit.enabled=true",
            "axiz.security.rate-limit.rules[0].name=payments",
            "axiz.security.rate-limit.rules[0].paths[0]=/payments/**",
            "axiz.security.rate-limit.rules[0].fail-open=false")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(RateLimiter.class);
              AxizSecurityProperties p = ctx.getBean(AxizSecurityProperties.class);
              AxizSecurityProperties.Rule rule = p.rateLimit().rules().get(0);
              assertFalse(rule.failOpen(true), "the rule overrides the global policy");
              assertTrue(p.rateLimit().failOpen());
            });
    servlet.run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimiter.class));
  }

  @Test
  void theReactiveChainHasTheSameDefaults() {
    reactive.run(
        ctx -> {
          org.springframework.web.server.WebFilter proxy =
              ctx.getBean(org.springframework.security.web.server.WebFilterChainProxy.class);
          assertEquals(0, exchange(proxy, "/actuator/health"));
          assertEquals(401, exchange(proxy, "/actuator/info"));
          assertEquals(401, exchange(proxy, "/swagger-ui/index.html"));
          assertEquals(401, exchange(proxy, "/payments"));
        });
    reactive
        .withPropertyValues("axiz.security.public-api-docs=true", "axiz.security.public-info=true")
        .run(
            ctx -> {
              org.springframework.web.server.WebFilter proxy =
                  ctx.getBean(org.springframework.security.web.server.WebFilterChainProxy.class);
              assertEquals(0, exchange(proxy, "/v3/api-docs"));
              assertEquals(0, exchange(proxy, "/actuator/info"));
            });
  }

  private static int exchange(org.springframework.web.server.WebFilter proxy, String path) {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
    AtomicInteger reached = new AtomicInteger();
    proxy.filter(exchange, e -> Mono.fromRunnable(reached::incrementAndGet)).block();
    if (reached.get() > 0) {
      return 0;
    }
    return exchange.getResponse().getStatusCode() == null
        ? -1
        : exchange.getResponse().getStatusCode().value();
  }

  // --- reactive caller context ---------------------------------------------------------------

  private static AxizAuthentication authentication() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuer(ISSUER)
            .audience(List.of("payments-api"))
            .subject("user-1")
            .claim("azp", "mobile-app")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    return new AxizJwtConverter(new ClaimsMapper(ClaimMapping.defaults())).convert(jwt);
  }

  @Test
  void aMissingOrEmptySecurityContextIsAnonymousNotAnError() {
    for (Mono<org.springframework.security.core.context.SecurityContext> context :
        List.<Mono<org.springframework.security.core.context.SecurityContext>>of(
            Mono.empty(), Mono.just(new SecurityContextImpl()))) {
      MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
      AtomicInteger calls = new AtomicInteger();
      assertDoesNotThrow(
          () ->
              new CallerContextWebFilter()
                  .filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet))
                  .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(context))
                  .block(Duration.ofSeconds(2)));
      assertEquals(1, calls.get(), "the chain runs exactly once");
    }
  }

  @Test
  void anAxizCallerEnrichesTheRequestContextOfTheChain() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    exchange.getAttributes().put(RequestContext.KEY, RequestMetadata.builder("c-1", "r-1").build());
    AtomicReference<RequestMetadata> seen = new AtomicReference<>();
    WebFilterChain chain =
        e ->
            Mono.deferContextual(
                c -> {
                  seen.set(c.get(RequestContext.KEY));
                  return Mono.empty();
                });
    new CallerContextWebFilter()
        .filter(exchange, chain)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication()))
        .block(Duration.ofSeconds(2));
    assertEquals("user-1", seen.get().subject());
    assertEquals("mobile-app", seen.get().clientId());
    RequestMetadata stored = exchange.getAttribute(RequestContext.KEY);
    assertEquals("user-1", stored.subject());
  }

  @Test
  void anotherKindOfAuthenticationLeavesTheContextAlone() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    RequestMetadata original = RequestMetadata.builder("c-1", "r-1").build();
    exchange.getAttributes().put(RequestContext.KEY, original);
    AtomicInteger calls = new AtomicInteger();
    new CallerContextWebFilter()
        .filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet))
        .contextWrite(
            ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken("x", "y")))
        .block(Duration.ofSeconds(2));
    assertEquals(1, calls.get());
    assertEquals(original, exchange.getAttribute(RequestContext.KEY));
  }

  // --- rate limits -------------------------------------------------------------------------------

  private static RateLimitRules oneRequestPerMinute(Boolean failOpen) {
    return new RateLimitRules(
        List.of(
            new AxizSecurityProperties.Rule(
                "payments",
                List.of("/payments/**"),
                1,
                Duration.ofMinutes(1),
                "client",
                failOpen)));
  }

  @Test
  void reactiveLimitsApplyToAxizCallersAndAnswer429() {
    RateLimitWebFilter filter =
        new RateLimitWebFilter(
            new pe.axiz.core.security.InMemoryAsyncRateLimiter(Clock.systemUTC()),
            oneRequestPerMinute(null),
            new SecurityProblems(null));
    AtomicInteger calls = new AtomicInteger();
    MockServerWebExchange first =
        MockServerWebExchange.from(MockServerHttpRequest.get("/payments/1"));
    MockServerWebExchange second =
        MockServerWebExchange.from(MockServerHttpRequest.get("/payments/2"));
    for (MockServerWebExchange e : List.of(first, second)) {
      filter
          .filter(e, x -> Mono.fromRunnable(calls::incrementAndGet))
          .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication()))
          .block(Duration.ofSeconds(2));
    }
    assertEquals(1, calls.get());
    assertEquals("0", first.getResponse().getHeaders().getFirst("RateLimit-Remaining"));
    assertEquals(429, second.getResponse().getStatusCode().value());
    assertTrue(second.getResponse().getHeaders().containsHeader("Retry-After"));

    // other paths, anonymous callers and empty contexts pass untouched
    MockServerWebExchange other = MockServerWebExchange.from(MockServerHttpRequest.get("/other"));
    filter.filter(other, x -> Mono.fromRunnable(calls::incrementAndGet)).block();
    MockServerWebExchange anonymous =
        MockServerWebExchange.from(MockServerHttpRequest.get("/payments/3"));
    filter
        .filter(anonymous, x -> Mono.fromRunnable(calls::incrementAndGet))
        .contextWrite(
            ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl())))
        .block(Duration.ofSeconds(2));
    assertEquals(3, calls.get());
  }

  @Test
  void eachRuleAppliesItsOwnFailurePolicy() throws Exception {
    AtomicReference<Boolean> policy = new AtomicReference<>();
    RateLimiter down =
        new RateLimiter() {
          @Override
          public Decision tryAcquire(String key, long limit, Duration window) {
            throw new AssertionError("the policy-aware variant must be used");
          }

          @Override
          public Decision tryAcquire(String key, long limit, Duration window, boolean failOpen) {
            policy.set(failOpen);
            return failOpen
                ? new Decision(true, -1, Duration.ZERO)
                : new Decision(false, 0, window);
          }
        };
    SecurityContextHolder.getContext().setAuthentication(authentication());
    try {
      MockHttpServletResponse closed = new MockHttpServletResponse();
      new RateLimitFilter(down, oneRequestPerMinute(false), new SecurityProblems(null), true)
          .doFilter(
              new MockHttpServletRequest("GET", "/payments/1"), closed, new MockFilterChain());
      assertEquals(false, policy.get());
      assertEquals(429, closed.getStatus());

      MockHttpServletResponse open = new MockHttpServletResponse();
      MockFilterChain app = new MockFilterChain();
      new RateLimitFilter(down, oneRequestPerMinute(null), new SecurityProblems(null), true)
          .doFilter(new MockHttpServletRequest("GET", "/payments/1"), open, app);
      assertEquals(true, policy.get(), "no rule policy: the global one");
      assertTrue(app.getRequest() != null);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void anonymousPathsCollectEverySwitch() {
    AxizSecurityProperties defaults =
        new AxizSecurityProperties(
            List.of(),
            List.of("RS256"),
            Duration.ofSeconds(30),
            null,
            List.of(),
            false,
            false,
            "SCOPE_actuator",
            null);
    assertTrue(defaults.anonymousPaths("/actuator").isEmpty());
    AxizSecurityProperties open =
        new AxizSecurityProperties(
            List.of(),
            List.of("RS256"),
            Duration.ofSeconds(30),
            null,
            List.of("/catalog/**"),
            true,
            true,
            "SCOPE_actuator",
            null);
    assertEquals(
        List.of(
            "/catalog/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/manage/info"),
        open.anonymousPaths("/manage"));
  }

  // --- decoders and servlet caller context ---------------------------------------------------

  @Test
  void decodersExistOnlyForTrustedIssuersAndAreReused() {
    pe.axiz.core.security.IssuerPolicy policy =
        AxizSecurityAutoConfiguration.issuerPolicy(
            List.of(
                new AxizSecurityProperties.Issuer(
                    ISSUER, "https://idp.example.com/realms/axiz/certs", List.of("payments-api")),
                new AxizSecurityProperties.Issuer(
                    "https://idp.example.com/realms/partners", null, List.of("payments-api"))));
    TrustedIssuerDecoders decoders =
        new TrustedIssuerDecoders(policy, List.of("RS256", "ES256"), Duration.ofSeconds(30));
    assertThat(decoders.servlet(ISSUER)).isSameAs(decoders.servlet(ISSUER));
    assertThat(decoders.reactive(ISSUER)).isSameAs(decoders.reactive(ISSUER));
    assertThat(decoders.servlet("https://evil.example.com")).isNull();
    assertThat(decoders.reactive("https://evil.example.com")).isNull();
    // without a JWKS URI the reactive decoder discovers the keys from the issuer on first use
    assertThat(decoders.reactive("https://idp.example.com/realms/partners")).isNotNull();
    assertThat(decoders.policy()).isSameAs(policy);
    Jwt good =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuer(ISSUER)
            .audience(List.of("payments-api"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    Jwt otherAudience =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuer(ISSUER)
            .audience(List.of("ledger-api"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    Jwt expired =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuer(ISSUER)
            .audience(List.of("payments-api"))
            .issuedAt(Instant.now().minusSeconds(600))
            .expiresAt(Instant.now().minusSeconds(300))
            .build();
    var validator = decoders.validator(policy.find(ISSUER).orElseThrow());
    assertFalse(validator.validate(good).hasErrors());
    assertTrue(validator.validate(otherAudience).hasErrors());
    assertTrue(validator.validate(expired).hasErrors());
    assertThrows(
        NullPointerException.class,
        () -> new TrustedIssuerDecoders(policy, List.of("none"), Duration.ofSeconds(30)));
  }

  @Test
  void theServletFilterAddsTheCallerOnlyForAxizTokens() throws Exception {
    AtomicReference<RequestMetadata> seen = new AtomicReference<>();
    AtomicReference<String> mdc = new AtomicReference<>();
    jakarta.servlet.FilterChain chain =
        (req, res) -> {
          seen.set(RequestContext.get());
          mdc.set(org.slf4j.MDC.get("clientId"));
        };
    try (RequestContext.Scope ignored =
        RequestContext.open(RequestMetadata.builder("c-1", "r-1").build())) {
      SecurityContextHolder.getContext().setAuthentication(authentication());
      new CallerContextFilter()
          .doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), chain);
      assertEquals("user-1", seen.get().subject());
      org.slf4j.MDC.put("probe", "x");
      boolean mdcWorks = "x".equals(org.slf4j.MDC.get("probe"));
      org.slf4j.MDC.remove("probe");
      assertEquals(mdcWorks ? "mobile-app" : null, mdc.get());
      assertEquals(null, org.slf4j.MDC.get("clientId"), "the MDC entry is removed afterwards");

      SecurityContextHolder.getContext()
          .setAuthentication(new TestingAuthenticationToken("x", "y"));
      new CallerContextFilter()
          .doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), chain);
      assertEquals(null, seen.get().subject(), "another kind of authentication adds nothing");
    } finally {
      SecurityContextHolder.clearContext();
    }
    SecurityContextHolder.getContext().setAuthentication(authentication());
    try {
      new CallerContextFilter()
          .doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), chain);
      assertEquals(null, seen.get(), "no request context: nothing to enrich");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
