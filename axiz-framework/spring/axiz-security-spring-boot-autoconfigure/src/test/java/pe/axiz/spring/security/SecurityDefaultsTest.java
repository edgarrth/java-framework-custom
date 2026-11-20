package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.security.ClaimMapping;
import pe.axiz.core.security.ClaimsMapper;
import pe.axiz.core.security.InMemoryAsyncRateLimiter;
import pe.axiz.core.security.InMemoryRateLimiter;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.RateLimitUnavailableException;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.spring.errors.AxizErrorsAutoConfiguration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

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

  record SchedulerConsumer(Scheduler scheduler) {}

  @Test
  void presentationSchedulersDoNotCompeteWithApplicationInjection() {
    var userScheduler = Schedulers.newSingle("application-owned");
    try {
      reactive
          .withConfiguration(AutoConfigurations.of(AxizErrorsAutoConfiguration.class))
          .withBean("applicationScheduler", Scheduler.class, () -> userScheduler)
          .withBean(SchedulerConsumer.class)
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(SchedulerConsumer.class).scheduler())
                    .isSameAs(userScheduler);
                assertThat(ctx.getBean("axizErrorRenderingScheduler")).isNotSameAs(userScheduler);
                assertThat(ctx.getBean("axizSecurityRenderingScheduler"))
                    .isNotSameAs(userScheduler);
              });
    } finally {
      userScheduler.dispose();
    }
  }

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
                          Map.of(
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
  void enabledRateLimitRequiresAnExplicitFailurePolicyForEveryRule() {
    servlet
        .withPropertyValues(
            "axiz.security.rate-limit.enabled=true",
            "axiz.security.rate-limit.store=memory",
            "axiz.config.environment=test",
            "axiz.security.rate-limit.rules[0].name=payments")
        .run(ctx -> assertThat(ctx).hasFailed());
    reactive
        .withPropertyValues(
            "axiz.security.rate-limit.enabled=true",
            "axiz.security.rate-limit.store=memory",
            "axiz.config.environment=test",
            "axiz.security.rate-limit.rules[0].name=payments")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void theRateLimitFilterIsAddedOnlyWhenEnabled() {
    servlet
        .withPropertyValues(
            "axiz.security.rate-limit.enabled=true",
            "axiz.security.rate-limit.store=memory",
            "axiz.config.environment=test",
            "axiz.security.rate-limit.rules[0].name=payments",
            "axiz.security.rate-limit.rules[0].paths[0]=/payments/**",
            "axiz.security.rate-limit.rules[0].fail-open=false")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(RateLimiter.class);
              AxizSecurityProperties p = ctx.getBean(AxizSecurityProperties.class);
              AxizSecurityProperties.Rule rule = p.rateLimit().rules().get(0);
              assertFalse(rule.failurePolicy());
            });
    servlet.run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimiter.class));
  }

  @Test
  void theReactiveChainHasTheSameDefaults() {
    reactive.run(
        ctx -> {
          WebFilter proxy = ctx.getBean(WebFilterChainProxy.class);
          assertEquals(0, exchange(proxy, "/actuator/health"));
          assertEquals(401, exchange(proxy, "/actuator/info"));
          assertEquals(401, exchange(proxy, "/swagger-ui/index.html"));
          assertEquals(401, exchange(proxy, "/payments"));
        });
    reactive
        .withPropertyValues("axiz.security.public-api-docs=true", "axiz.security.public-info=true")
        .run(
            ctx -> {
              WebFilter proxy = ctx.getBean(WebFilterChainProxy.class);
              assertEquals(0, exchange(proxy, "/v3/api-docs"));
              assertEquals(0, exchange(proxy, "/actuator/info"));
            });
  }

  private static int exchange(WebFilter proxy, String path) {
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
    for (Mono<SecurityContext> context :
        List.<Mono<SecurityContext>>of(Mono.empty(), Mono.just(new SecurityContextImpl()))) {
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

  @Test
  void bothSecurityChainsAuthorizeBeforeConsumingAnAnonymousQuota() {
    String[] quota = {
      "axiz.security.public-paths[0]=/public/**",
      "axiz.security.rate-limit.enabled=true",
      "axiz.security.rate-limit.store=memory",
      "axiz.config.environment=test",
      "axiz.security.rate-limit.rules[0].name=public",
      "axiz.security.rate-limit.rules[0].paths[0]=/**",
      "axiz.security.rate-limit.rules[0].key=anonymous-ip",
      "axiz.security.rate-limit.rules[0].fail-open=false"
    };
    var servletKeys = new ArrayList<String>();
    servlet
        .withPropertyValues(quota)
        .withBean(
            RateLimiter.class,
            () ->
                (key, limit, window) -> {
                  servletKeys.add(key);
                  return new RateLimiter.Decision(true, 1, Duration.ZERO);
                })
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              Filter chain = ctx.getBean("springSecurityFilterChain", Filter.class);
              assertEquals(401, call(chain, "/private", null));
              assertTrue(servletKeys.isEmpty());
              assertEquals(0, call(chain, "/public/status", null));
              assertEquals(1, servletKeys.size());
            });
    var reactiveKeys = new ArrayList<String>();
    reactive
        .withPropertyValues(quota)
        .withBean(
            AsyncRateLimiter.class,
            () ->
                (key, limit, window, failOpen) -> {
                  reactiveKeys.add(key);
                  return CompletableFuture.completedFuture(
                      new RateLimiter.Decision(true, 1, Duration.ZERO));
                })
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              WebFilter chain = ctx.getBean(WebFilterChainProxy.class);
              assertEquals(401, exchange(chain, "/private"));
              assertTrue(reactiveKeys.isEmpty());
              var request =
                  MockServerWebExchange.from(
                      MockServerHttpRequest.get("/public/status")
                          .remoteAddress(new InetSocketAddress("192.0.2.1", 1234)));
              var reached = new AtomicInteger();
              chain.filter(request, e -> Mono.fromRunnable(reached::incrementAndGet)).block();
              assertEquals(1, reached.get());
              assertEquals(1, reactiveKeys.size());
            });
  }

  @Test
  void anonymousGatewayLimitsUseConnectionAddressesAndIgnoreForwardedHeaders() throws Exception {
    var rules =
        new RateLimitRules(
            List.of(
                new AxizSecurityProperties.Rule(
                    "callers", List.of("/public/**"), 1, Duration.ofMinutes(1), "client", false),
                new AxizSecurityProperties.Rule(
                    "anonymous",
                    List.of("/public/**"),
                    1,
                    Duration.ofMinutes(1),
                    "anonymous-ip",
                    false)));
    var sync =
        new RateLimitFilter(
            new InMemoryRateLimiter(Clock.systemUTC()), rules, new SecurityProblems(null));
    var async =
        new RateLimitWebFilter(
            new InMemoryAsyncRateLimiter(Clock.systemUTC()),
            rules,
            new SecurityProblems(null),
            Schedulers.boundedElastic());
    SecurityContextHolder.clearContext();
    AtomicInteger servletCalls = new AtomicInteger();
    AtomicInteger reactiveCalls = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      String address = i < 2 ? "192.0.2.1" : "192.0.2.2";
      var request = new MockHttpServletRequest("GET", "/public/status");
      request.setRemoteAddr(address);
      request.addHeader("X-Forwarded-For", "198.51.100." + i);
      var response = new MockHttpServletResponse();
      sync.doFilter(request, response, (q, r) -> servletCalls.incrementAndGet());
      assertEquals(i == 1 ? 429 : 200, response.getStatus());
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/public/status")
                  .remoteAddress(new InetSocketAddress(address, 1234))
                  .header("X-Forwarded-For", "198.51.100." + i));
      async
          .filter(exchange, e -> Mono.fromRunnable(reactiveCalls::incrementAndGet))
          .block(Duration.ofSeconds(2));
      if (i == 1) assertEquals(429, exchange.getResponse().getStatusCode().value());
    }
    assertEquals(2, servletCalls.get());
    assertEquals(2, reactiveCalls.get());
    var missingAddress = MockServerWebExchange.from(MockServerHttpRequest.get("/public/status"));
    async
        .filter(
            missingAddress, e -> Mono.error(new AssertionError("Missing address bypassed quota")))
        .block(Duration.ofSeconds(2));
    assertEquals(503, missingAddress.getResponse().getStatusCode().value());
  }

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
            new InMemoryAsyncRateLimiter(Clock.systemUTC()),
            oneRequestPerMinute(true),
            new SecurityProblems(null),
            Schedulers.boundedElastic());
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
            if (failOpen) return new Decision(true, -1, Duration.ZERO);
            throw new RateLimitUnavailableException(new IllegalStateException("store down"));
          }
        };
    SecurityContextHolder.getContext().setAuthentication(authentication());
    try {
      MockHttpServletResponse closed = new MockHttpServletResponse();
      new RateLimitFilter(down, oneRequestPerMinute(false), new SecurityProblems(null))
          .doFilter(
              new MockHttpServletRequest("GET", "/payments/1"), closed, new MockFilterChain());
      assertEquals(false, policy.get());
      assertEquals(503, closed.getStatus());

      MockHttpServletResponse open = new MockHttpServletResponse();
      MockFilterChain app = new MockFilterChain();
      new RateLimitFilter(down, oneRequestPerMinute(true), new SecurityProblems(null))
          .doFilter(new MockHttpServletRequest("GET", "/payments/1"), open, app);
      assertEquals(true, policy.get(), "explicit fail-open");
      assertTrue(app.getRequest() != null);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void reactiveUnavailableStoreAnswers503BeforeInvokingTheHandler() {
    AsyncRateLimiter down =
        (key, limit, window, open) ->
            open
                ? CompletableFuture.completedFuture(
                    new RateLimiter.Decision(true, -1, Duration.ZERO))
                : CompletableFuture.failedFuture(
                    new RateLimitUnavailableException(new IllegalStateException("offline")));
    for (boolean open : List.of(false, true)) {
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/payments/1"));
      var calls = new AtomicInteger();
      new RateLimitWebFilter(
              down,
              oneRequestPerMinute(open),
              new SecurityProblems(null),
              Schedulers.boundedElastic())
          .filter(exchange, e -> Mono.fromRunnable(calls::incrementAndGet))
          .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication()))
          .block(Duration.ofSeconds(5));
      assertEquals(open ? 1 : 0, calls.get());
      if (!open) assertEquals(503, exchange.getResponse().getStatusCode().value());
    }
  }

  @Test
  void anonymousPathsCollectEverySwitch() {
    AxizSecurityProperties defaults =
        AxizSecurityProperties.builder()
            .issuers(List.of())
            .algorithms(List.of("RS256"))
            .clockSkew(Duration.ofSeconds(30))
            .claims(AxizSecurityProperties.Claims.builder().build())
            .publicPaths(List.of())
            .publicApiDocs(false)
            .publicInfo(false)
            .actuatorAuthority("SCOPE_actuator")
            .rateLimit(new AxizSecurityProperties.RateLimit(false, null, List.of()))
            .authorization(new AxizSecurityProperties.Authorization("fail", List.of()))
            .jwks(new AxizSecurityProperties.Jwks(Duration.ofSeconds(2), Duration.ofSeconds(3)))
            .allowHttpJwksInCluster(false)
            .build();
    assertTrue(defaults.anonymousPaths("/actuator").isEmpty());
    AxizSecurityProperties open =
        AxizSecurityProperties.builder()
            .issuers(List.of())
            .algorithms(List.of("RS256"))
            .clockSkew(Duration.ofSeconds(30))
            .claims(AxizSecurityProperties.Claims.builder().build())
            .publicPaths(List.of("/catalog/**"))
            .publicApiDocs(true)
            .publicInfo(true)
            .actuatorAuthority("SCOPE_actuator")
            .rateLimit(new AxizSecurityProperties.RateLimit(false, null, List.of()))
            .authorization(new AxizSecurityProperties.Authorization("fail", List.of()))
            .jwks(new AxizSecurityProperties.Jwks(Duration.ofSeconds(2), Duration.ofSeconds(3)))
            .allowHttpJwksInCluster(false)
            .build();
    assertEquals(
        List.of(
            "/catalog/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/manage/info"),
        open.anonymousPaths("/manage"));
  }

  // --- decoders and servlet caller context ---------------------------------------------------

  @Test
  void decodersExistOnlyForTrustedIssuersAndAreReused() {
    IssuerPolicy policy =
        AxizSecurityAutoConfiguration.issuerPolicy(
            List.of(
                new AxizSecurityProperties.Issuer(
                    ISSUER,
                    "https://idp.example.com/realms/axiz/certs",
                    List.of("payments-api"),
                    JwtAccessProfile.COMPATIBLE),
                new AxizSecurityProperties.Issuer(
                    "https://idp.example.com/realms/partners",
                    null,
                    List.of("payments-api"),
                    JwtAccessProfile.COMPATIBLE)));
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
    FilterChain chain =
        (req, res) -> {
          seen.set(RequestContext.get());
          mdc.set(MDC.get("clientId"));
        };
    try (RequestContext.Scope ignored =
        RequestContext.open(RequestMetadata.builder("c-1", "r-1").build())) {
      SecurityContextHolder.getContext().setAuthentication(authentication());
      new CallerContextFilter()
          .doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), chain);
      assertEquals("user-1", seen.get().subject());
      MDC.put("probe", "x");
      boolean mdcWorks = "x".equals(MDC.get("probe"));
      MDC.remove("probe");
      assertEquals(mdcWorks ? "mobile-app" : null, mdc.get());
      assertEquals(null, MDC.get("clientId"), "the MDC entry is removed afterwards");

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

  @Test
  void selectedRateLimitStoreFailsClosedOnBothStacks() {
    String[] base = {
      "axiz.security.rate-limit.enabled=true",
      "axiz.security.rate-limit.rules[0].name=payments",
      "axiz.security.rate-limit.rules[0].fail-open=false"
    };
    for (String store : new String[] {"memory", "redis"}) {
      servlet
          .withPropertyValues(base)
          .withPropertyValues(
              "axiz.security.rate-limit.store=" + store,
              "axiz.config.environment=prod",
              "spring.profiles.active=local")
          .run(ctx -> assertThat(ctx).hasFailed());
      reactive
          .withPropertyValues(base)
          .withPropertyValues(
              "axiz.security.rate-limit.store=" + store,
              "axiz.config.environment=prod",
              "spring.profiles.active=local")
          .run(ctx -> assertThat(ctx).hasFailed());
    }
    for (String environment : new String[] {"local", "test", "ci"}) {
      servlet
          .withPropertyValues(base)
          .withPropertyValues(
              "axiz.security.rate-limit.store=memory", "axiz.config.environment=" + environment)
          .run(ctx -> assertThat(ctx).hasSingleBean(RateLimiter.class));
      reactive
          .withPropertyValues(base)
          .withPropertyValues(
              "axiz.security.rate-limit.store=memory", "axiz.config.environment=" + environment)
          .run(ctx -> assertThat(ctx).hasSingleBean(pe.axiz.core.security.AsyncRateLimiter.class));
    }
    servlet.withPropertyValues(base).run(ctx -> assertThat(ctx).hasFailed());
    reactive.withPropertyValues(base).run(ctx -> assertThat(ctx).hasFailed());

    // A new integration supplies a port; the neutral base has no provider enum to extend.
    var custom = mock(RateLimiter.class);
    servlet
        .withPropertyValues(base)
        .withPropertyValues("axiz.security.rate-limit.store=application-provider")
        .withBean(RateLimiter.class, () -> custom)
        .run(ctx -> assertThat(ctx.getBean(RateLimiter.class)).isSameAs(custom));
    var nativeCustom = mock(AsyncRateLimiter.class);
    reactive
        .withPropertyValues(base)
        .withPropertyValues("axiz.security.rate-limit.store=application-provider")
        .withBean(AsyncRateLimiter.class, () -> nativeCustom)
        .run(ctx -> assertThat(ctx.getBean(AsyncRateLimiter.class)).isSameAs(nativeCustom));
  }
}
