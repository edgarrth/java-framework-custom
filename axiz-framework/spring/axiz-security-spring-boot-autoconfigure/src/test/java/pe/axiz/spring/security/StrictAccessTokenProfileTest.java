package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tokens adversariales firmados a través de ambas cadenas de seguridad efectivas y un JWKS real en
 * loopback.
 */
class StrictAccessTokenProfileTest {
  private static final String ISSUER = "https://profiles.example";
  private static final String PATH = "/tenant-contract";
  private static HttpServer server;
  private static RSAKey trusted;
  private static RSAKey attacker;
  private static final AtomicInteger ATTACKER_REQUESTS = new AtomicInteger();

  @BeforeAll
  static void start() throws Exception {
    trusted = new RSAKeyGenerator(2048).keyID("trusted").generate();
    attacker = new RSAKeyGenerator(2048).keyID("attacker").generate();
    byte[] keys = new JWKSet(trusted.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/keys",
        request -> {
          request.getResponseHeaders().set("Content-Type", "application/json");
          request.sendResponseHeaders(200, keys.length);
          try (var out = request.getResponseBody()) {
            out.write(keys);
          }
        });
    server.createContext(
        "/attacker",
        request -> {
          ATTACKER_REQUESTS.incrementAndGet();
          request.sendResponseHeaders(500, -1);
          request.close();
        });
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  private static String[] settings(String profile) {
    return new String[] {
      "axiz.config.environment=test",
      "axiz.security.issuers[0].issuer=" + ISSUER,
      "axiz.security.issuers[0].jwk-set-uri=http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/keys",
      "axiz.security.issuers[0].audiences[0]=api",
      "axiz.security.issuers[0].profile=" + profile
    };
  }

  private static String token(String variant) throws Exception {
    return token(variant, true);
  }

  private static String token(String variant, boolean strict) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("api")
            .subject("user")
            .claim("client_id", "client")
            .jwtID("operation-token")
            .issueTime(Date.from(Instant.now().minusSeconds(60)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    switch (variant) {
      case "missing-exp" -> claims.expirationTime(null);
      case "missing-issuer" -> claims.issuer(null);
      case "missing-audience" -> claims.audience(List.of());
      case "other-audience" -> claims.audience("other");
      case "other-issuer" -> claims.issuer("https://unknown.example");
      case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(90)));
      case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(300)));
      case "missing-client" -> claims.claim("client_id", null);
      case "missing-jti" -> claims.jwtID(null);
      case "missing-iat" -> claims.issueTime(null);
      case "missing-subject" -> claims.subject(null);
      case "blank-client" -> claims.claim("client_id", " ");
      case "numeric-client" -> claims.claim("client_id", 7);
      case "numeric-subject" -> claims.claim("sub", 7);
      case "numeric-jti" -> claims.claim("jti", 7);
      case "oversized-jti" -> claims.jwtID("x".repeat(1025));
      default -> {}
    }
    if (variant.equals("unsigned")) return new PlainJWT(claims.build()).serialize();
    JWSAlgorithm algorithm =
        variant.equals("rsa256") || variant.equals("compatible")
            ? JWSAlgorithm.RS256
            : variant.equals("hmac-confusion") ? JWSAlgorithm.HS256 : JWSAlgorithm.PS256;
    var header = new JWSHeader.Builder(algorithm).keyID("trusted");
    if (!variant.equals("missing-type"))
      header.type(
          new JOSEObjectType(
              switch (variant) {
                case "id-token" -> "id_token";
                case "compatible" -> "JWT";
                case "full-media-type" -> "application/at+jwt";
                default -> strict ? "at+jwt" : "JWT";
              }));
    if (variant.equals("unknown-kid")) header.keyID("unknown");
    if (variant.equals("embedded-jwk")) header.jwk(attacker.toPublicJWK());
    if (variant.equals("attacker-jku"))
      header.jwkURL(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/attacker"));
    var signed = new SignedJWT(header.build(), claims.build());
    if (variant.equals("hmac-confusion"))
      signed.sign(new MACSigner(trusted.toRSAPublicKey().getEncoded()));
    else
      signed.sign(
          new RSASSASigner(
              List.of("embedded-jwk", "attacker-jku", "wrong-signature").contains(variant)
                  ? attacker
                  : trusted));
    return signed.serialize();
  }

  private record Case(String name, String token, int status) {}

  @ParameterizedTest
  @ValueSource(strings = {"strict", "compatible"})
  void profilesAreDisjointAndMalformedTokensReturn401OnBothStacks(String profile) throws Exception {
    List<Case> cases = new ArrayList<>();
    boolean strict = profile.equals("strict");
    cases.add(new Case("positive", token(strict ? "valid" : "compatible"), 200));
    cases.add(new Case("other-profile", token(strict ? "compatible" : "valid"), 401));
    for (String variant :
        List.of(
            "unsigned",
            "hmac-confusion",
            "missing-exp",
            "missing-issuer",
            "missing-audience",
            "other-audience",
            "other-issuer",
            "expired",
            "future",
            "unknown-kid",
            "embedded-jwk",
            "attacker-jku",
            "wrong-signature",
            "id-token")) cases.add(new Case(variant, token(variant, strict), 401));
    if (strict) {
      for (String variant :
          List.of(
              "rsa256",
              "missing-type",
              "missing-client",
              "missing-jti",
              "missing-iat",
              "missing-subject",
              "blank-client",
              "numeric-client",
              "numeric-subject",
              "numeric-jti",
              "oversized-jti")) cases.add(new Case(variant, token(variant), 401));
      cases.add(new Case("full-media-type", token("full-media-type"), 200));
    }
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withUserConfiguration(TenantClaimHttpTest.Servlet.class)
        .withPropertyValues(settings(profile))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var client =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                      .build();
              for (Case c : cases)
                assertEquals(
                    c.status(),
                    client
                        .perform(get(PATH).header("Authorization", "Bearer " + c.token()))
                        .andReturn()
                        .getResponse()
                        .getStatus(),
                    c.name());
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withUserConfiguration(TenantClaimHttpTest.Reactive.class)
        .withPropertyValues(settings(profile))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var client = WebTestClient.bindToApplicationContext(context).build();
              for (Case c : cases)
                client
                    .get()
                    .uri(PATH)
                    .header("Authorization", "Bearer " + c.token())
                    .exchange()
                    .expectStatus()
                    .isEqualTo(c.status());
            });
    assertEquals(0, ATTACKER_REQUESTS.get(), "Token-provided key URLs are never fetched");
  }

  @Test
  void strictProfileCannotStartWithOnlyRs256() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityAutoConfiguration.class))
        .withUserConfiguration(TenantClaimHttpTest.Servlet.class)
        .withPropertyValues(settings("strict"))
        .withPropertyValues("axiz.security.algorithms[0]=RS256")
        .run(context -> assertThat(context).hasFailed());
  }
}
