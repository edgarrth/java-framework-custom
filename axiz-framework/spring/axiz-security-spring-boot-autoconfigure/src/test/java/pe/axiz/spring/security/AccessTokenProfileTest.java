package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.security.AxizPrincipal;
import pe.axiz.core.security.ClaimMapping;
import pe.axiz.core.security.ClaimsMapper;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.TrustedIssuer;

/**
 * Real signed tokens against a JWKS on loopback, on both stacks: what an access token needs to be
 * accepted (RFC 9068: an expiration among them).
 */
class AccessTokenProfileTest {

  private static final String ISSUER = "https://idp.example.test/realms/axiz";
  private static final Duration T = Duration.ofSeconds(5);

  private static RSAKey key;
  private static HttpServer jwks;
  private static TrustedIssuerDecoders decoders;

  @BeforeAll
  static void start() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext(
        "/certs",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    jwks.start();
    URI certs = URI.create("http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs");
    decoders =
        new TrustedIssuerDecoders(
            new IssuerPolicy(List.of(new TrustedIssuer(ISSUER, certs, List.of("payments-api")))),
            List.of("RS256"),
            Duration.ofSeconds(30));
  }

  @AfterAll
  static void stop() {
    jwks.stop(0);
  }

  private static String token(JWSAlgorithm alg, UnaryOperator<JWTClaimsSet.Builder> claims)
      throws Exception {
    JWTClaimsSet.Builder base =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("payments-api")
            .subject("customer-1")
            .claim("azp", "mobile")
            .issueTime(Date.from(Instant.now().minusSeconds(60)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(alg).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
            claims.apply(base).build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static String decode(boolean reactive, String token) {
    if (reactive) {
      return decoders.reactive(ISSUER).decode(token).block(T).getSubject();
    }
    return decoders.servlet(ISSUER).decode(token).getSubject();
  }

  @ParameterizedTest(name = "reactive={0}")
  @ValueSource(booleans = {false, true})
  void signedUsernamesCannotPromoteUsersAndTrustedMarkersStillIdentifyApplications(boolean reactive)
      throws Exception {
    AxizJwtConverter converter = new AxizJwtConverter(new ClaimsMapper(ClaimMapping.defaults()));
    for (boolean service : List.of(false, true)) {
      String signed =
          token(
              JWSAlgorithm.RS256,
              b -> {
                b.claim("preferred_username", "service-account-mobile")
                    .claim("scope", "payments:read");
                return service ? b.claim("idtyp", "app") : b;
              });
      var jwt =
          reactive
              ? decoders.reactive(ISSUER).decode(signed).block(T)
              : decoders.servlet(ISSUER).decode(signed);
      var authentication = converter.convert(jwt);
      var principal = (AxizPrincipal) authentication.getPrincipal();
      assertEquals(service, principal.isMachine());
      assertEquals(service ? "mobile" : "customer-1", principal.subject());
      assertThat(authentication.getAuthorities())
          .extracting("authority")
          .containsExactly("SCOPE_payments:read");
    }
    String missingClient = token(JWSAlgorithm.RS256, b -> b.claim("azp", null));
    var jwt =
        reactive
            ? decoders.reactive(ISSUER).decode(missingClient).block(T)
            : decoders.servlet(ISSUER).decode(missingClient);
    assertThrows(AccessException.class, () -> converter.convert(jwt));
  }

  private static void rejected(boolean reactive, String token, @Nullable String reason) {
    JwtException e = assertThrows(JwtException.class, () -> decode(reactive, token));
    if (reason != null) {
      assertThat(e.getMessage()).contains(reason);
    }
  }

  @ParameterizedTest(name = "reactive={0}")
  @ValueSource(booleans = {false, true})
  void signedMalformedIdentitiesAreRejectedBeforeAuthentication(boolean reactive) throws Exception {
    ClaimsMapper mapper = new ClaimsMapper(ClaimMapping.defaults());
    for (String clientJson :
        List.of(
            "[\"app\"]", "{\"id\":\"app\"}", "123", "true", "\"app\\ud800\"", "\"app\\udc00\"")) {
      // Construct JSON directly: serializing a Java String could replace an unpaired surrogate
      // before signing it and would not exercise the signed malformed claim.
      String claims =
          "{\"iss\":\""
              + ISSUER
              + "\",\"aud\":\"payments-api\",\"sub\":\"customer-1\",\"azp\":"
              + clientJson
              + ",\"exp\":"
              + Instant.now().plusSeconds(300).getEpochSecond()
              + "}";
      JWSObject jwt =
          new JWSObject(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
              new Payload(claims));
      jwt.sign(new RSASSASigner(key));
      String signed = jwt.serialize();
      assertThrows(
          AccessException.class,
          () ->
              mapper.map(
                  (reactive
                          ? decoders.reactive(ISSUER).decode(signed).block(T)
                          : decoders.servlet(ISSUER).decode(signed))
                      .getClaims()));
    }
    String signed = token(JWSAlgorithm.RS256, c -> c.claim("azp", "app\uD83D\uDE00"));
    assertEquals(
        "app\uD83D\uDE00",
        mapper
            .map(
                (reactive
                        ? decoders.reactive(ISSUER).decode(signed).block(T)
                        : decoders.servlet(ISSUER).decode(signed))
                    .getClaims())
            .clientId());
  }

  @ParameterizedTest(name = "reactive={0}")
  @ValueSource(booleans = {false, true})
  void aValidTokenIsAccepted(boolean reactive) throws Exception {
    assertEquals("customer-1", decode(reactive, token(JWSAlgorithm.RS256, c -> c)));
  }

  @ParameterizedTest(name = "reactive={0}")
  @ValueSource(booleans = {false, true})
  void aTokenWithoutExpirationIsRejected(boolean reactive) throws Exception {
    rejected(reactive, token(JWSAlgorithm.RS256, c -> c.expirationTime(null)), "exp");
  }

  @ParameterizedTest(name = "reactive={0}")
  @ValueSource(booleans = {false, true})
  void expiredAndNotYetValidTokensAreRejected(boolean reactive) throws Exception {
    rejected(
        reactive,
        token(
            JWSAlgorithm.RS256,
            c ->
                c.issueTime(Date.from(Instant.now().minusSeconds(600)))
                    .expirationTime(Date.from(Instant.now().minusSeconds(120)))),
        "expired");
    rejected(
        reactive,
        token(JWSAlgorithm.RS256, c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(600)))),
        null);
    assertEquals(
        "customer-1",
        decode(
            reactive,
            token(
                JWSAlgorithm.RS256,
                c -> c.expirationTime(Date.from(Instant.now().minusSeconds(10))))),
        "within the clock skew");
  }

  @ParameterizedTest(name = "reactive={0}")
  @ValueSource(booleans = {false, true})
  void anotherAudienceIssuerOrAlgorithmIsRejected(boolean reactive) throws Exception {
    rejected(reactive, token(JWSAlgorithm.RS256, c -> c.audience("ledger-api")), "aud");
    rejected(reactive, token(JWSAlgorithm.RS256, c -> c.issuer("https://evil.example")), "iss");
    rejected(reactive, token(JWSAlgorithm.RS512, c -> c), null);
  }
}
