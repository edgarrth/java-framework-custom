package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.TrustedIssuer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Network fixtures exercise discovery, key reads, partial bodies and actual security chains. */
class JwksAvailabilityTest {
  private static final Duration LIMIT = Duration.ofSeconds(4);
  private static final RSAKey KEY = key();

  private static RSAKey key() {
    try {
      return new RSAKeyGenerator(2048).keyID("fixture").generate();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  static String signed(String issuer) {
    try {
      var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(),
              new JWTClaimsSet.Builder()
                  .issuer(issuer)
                  .subject("customer")
                  .audience("api")
                  .claim("azp", "mobile")
                  .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                  .build());
      jwt.sign(new RSASSASigner(KEY));
      return jwt.serialize();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  static TrustedIssuerDecoders decoders(Fixture fixture, boolean discovery) {
    return decoders(
        fixture,
        discovery,
        new AxizSecurityProperties.Jwks(Duration.ofMillis(150), Duration.ofMillis(250)));
  }

  static TrustedIssuerDecoders decoders(
      Fixture fixture, boolean discovery, AxizSecurityProperties.Jwks timeouts) {
    return new TrustedIssuerDecoders(
        new IssuerPolicy(
            List.of(
                new TrustedIssuer(
                    fixture.issuer(),
                    discovery ? null : URI.create(fixture.issuer() + "/keys"),
                    List.of("api")))),
        List.of("RS256"),
        Duration.ZERO,
        timeouts,
        true);
  }

  private static Jwt decode(TrustedIssuerDecoders decoders, Fixture fixture, boolean reactive) {
    return reactive
        ? decoders.reactive(fixture.issuer()).decode(signed(fixture.issuer())).block(LIMIT)
        : decoders.servlet(fixture.issuer()).decode(signed(fixture.issuer()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void discoveryAndKeysHaveIndependentBoundsAndNoRequestStorm(boolean reactive) throws Exception {
    for (boolean discovery : List.of(false, true)) {
      try (var fixture = new Fixture();
          var decoders = decoders(fixture, discovery)) {
        if (discovery) fixture.slowMetadata = true;
        else fixture.slowKeys = true;
        long start = System.nanoTime();
        assertThrows(IssuerUnavailableException.class, () -> decode(decoders, fixture, reactive));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        for (int i = 0; i < 20; i++)
          assertThrows(IssuerUnavailableException.class, () -> decode(decoders, fixture, reactive));
        assertThat(discovery ? fixture.metadata.get() : fixture.keys.get()).isEqualTo(1);
        assertThat(decoders.servlet("https://forged.example")).isNull();
        assertThat(decoders.reactive("https://forged.example")).isNull();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidDiscoveryRedirectsAndOversizedKeysFailClosed(boolean reactive) throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      try (var fixture = new Fixture();
          var decoders = decoders(fixture, true)) {
        fixture.wrongIssuer = kind == 0;
        fixture.redirect = kind == 1;
        fixture.oversized = kind == 2;
        assertThrows(IssuerUnavailableException.class, () -> decode(decoders, fixture, reactive));
        assertThat(fixture.metadata).hasValue(1);
        assertThat(fixture.keys).hasValue(kind == 2 ? 1 : 0);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void outageRecoversAfterBackoffAndKeysRemainCached(boolean reactive) throws Exception {
    try (var fixture = new Fixture();
        var decoders = decoders(fixture, true)) {
      fixture.unavailable = true;
      assertThrows(IssuerUnavailableException.class, () -> decode(decoders, fixture, reactive));
      fixture.unavailable = false;
      Thread.sleep(1100);
      assertThat(decode(decoders, fixture, reactive).getSubject()).isEqualTo("customer");
      fixture.unavailable = true;
      assertThat(decode(decoders, fixture, reactive).getSubject()).isEqualTo("customer");
      assertThat(fixture.metadata).hasValue(2);
      assertThat(fixture.keys).hasValue(1);
    }
  }

  @Test
  void nativeConcurrencyCoalescesFailedEndpointAttempts() throws Exception {
    try (var fixture = new Fixture();
        var decoders = decoders(fixture, true)) {
      fixture.slowMetadata = true;
      var results =
          Flux.range(0, 64)
              .flatMap(
                  index ->
                      decoders
                          .reactive(fixture.issuer())
                          .decode(signed(fixture.issuer()))
                          .map(jwt -> false)
                          .onErrorResume(
                              IssuerUnavailableException.class, error -> Mono.just(true)),
                  64)
              .collectList()
              .block(LIMIT);
      assertThat(results).hasSize(64).containsOnly(true);
      assertThat(fixture.metadata).hasValue(1);
      assertThat(fixture.keys).hasValue(0);
    }
  }

  @Test
  void insecureClusterEndpointsNeedExplicitOptInAndDeadlinesAreBounded() {
    var policy =
        new IssuerPolicy(
            List.of(new TrustedIssuer("http://idp.svc.cluster.local", null, List.of("api"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrustedIssuerDecoders(policy, List.of("RS256"), Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizSecurityProperties.Jwks(Duration.ZERO, Duration.ofSeconds(3)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizSecurityProperties.Jwks(Duration.ofSeconds(2), Duration.ofSeconds(11)));
  }

  @Test
  void realServletAndReactiveSecurityChainsReport503RatherThanAcceptingTheToken() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.unavailable = true;
      String[] properties = {
        "axiz.config.environment=test",
        "axiz.security.issuers[0].issuer=" + fixture.issuer(),
        "axiz.security.issuers[0].jwk-set-uri=" + fixture.issuer() + "/keys",
        "axiz.security.issuers[0].audiences[0]=api"
      };
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
          .withPropertyValues(properties)
          .withUserConfiguration(TenantClaimHttpTest.Servlet.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                    .build()
                    .perform(
                        get("/tenant-contract")
                            .header("Authorization", "Bearer " + signed(fixture.issuer())))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().string("Cache-Control", "no-store"));
              });
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
          .withPropertyValues(properties)
          .withUserConfiguration(TenantClaimHttpTest.Reactive.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                WebTestClient.bindToApplicationContext(context)
                    .build()
                    .get()
                    .uri("/tenant-contract")
                    .header("Authorization", "Bearer " + signed(fixture.issuer()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .valueEquals("Retry-After", "1")
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store");
              });
    }
  }

  static final class Fixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger metadata = new AtomicInteger();
    final AtomicInteger keys = new AtomicInteger();
    volatile boolean slowMetadata;
    volatile boolean slowKeys;
    volatile boolean wrongIssuer;
    volatile boolean redirect;
    volatile boolean oversized;
    volatile boolean unavailable;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/.well-known/openid-configuration",
          exchange -> {
            metadata.incrementAndGet();
            if (redirect) {
              exchange.getResponseHeaders().set("Location", issuer() + "/keys");
              exchange.sendResponseHeaders(302, -1);
              exchange.close();
              return;
            }
            respond(
                exchange,
                "{\"issuer\":\""
                    + (wrongIssuer ? "https://wrong.example" : issuer())
                    + "\",\"jwks_uri\":\""
                    + issuer()
                    + "/keys\"}",
                slowMetadata);
          });
      server.createContext(
          "/keys",
          exchange -> {
            keys.incrementAndGet();
            respond(
                exchange,
                oversized ? "x".repeat(140_000) : new JWKSet(KEY.toPublicJWK()).toString(),
                slowKeys);
          });
      server.start();
    }

    String issuer() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(HttpExchange exchange, String text, boolean slow) throws IOException {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(unavailable ? 503 : 200, bytes.length);
      try (var body = exchange.getResponseBody()) {
        if (slow) {
          body.write(bytes, 0, 1);
          body.flush();
          try {
            TimeUnit.SECONDS.sleep(2);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
        body.write(bytes, slow ? 1 : 0, bytes.length - (slow ? 1 : 0));
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
