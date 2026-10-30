package pe.axiz.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.AccessException;

class SecurityTest {

  private final ClaimsMapper mapper = new ClaimsMapper(ClaimMapping.defaults());

  @Test
  void identityClaimsRequireStringsAndWellFormedUnicode() {
    for (String claim : List.of("iss", "sub", "azp", "tenant")) {
      for (Object invalid :
          List.of(List.of("app"), Map.of("id", "app"), true, 123, "app\uD800", "app\uDC00")) {
        var claims =
            new java.util.HashMap<String, Object>(
                Map.of(
                    "iss", "https://idp.example", "sub", "user", "azp", "app", "tenant", "bank"));
        claims.put(claim, invalid);
        assertThrows(AccessException.class, () -> mapper.map(claims), claim);
      }
    }
    // A malformed primary claim cannot fall through to a valid alternative.
    assertThrows(
        AccessException.class,
        () ->
            mapper.map(
                Map.of("iss", "i", "sub", "user", "azp", List.of("app"), "client_id", "fallback")));
    assertThrows(AccessException.class, () -> mapper.map(Map.of("iss", "i", "client_id", 123)));
    AxizPrincipal valid =
        mapper.map(
            Map.of(
                "iss",
                "i",
                "sub",
                "josé\uD83D\uDE00",
                "azp",
                "[app]",
                "tenant",
                "bank",
                "aud",
                List.of("api"),
                "roles",
                List.of("customer")));
    assertEquals("josé\uD83D\uDE00", valid.subject());
    assertEquals("[app]", valid.clientId());
    assertEquals(List.of("api"), valid.audience());
    assertEquals(Set.of("customer"), valid.roles());
  }

  @Test
  void identityClaimsWithControlCharactersAreRejected() {
    for (String claim : List.of("iss", "sub", "azp", "tenant")) {
      java.util.HashMap<String, Object> claims =
          new java.util.HashMap<>(
              Map.of("iss", "https://idp.example", "sub", "user-1", "azp", "app", "tenant", "t1"));
      claims.put(claim, claims.get(claim) + "\u0000\u0002other");
      assertThrows(AccessException.class, () -> mapper.map(claims), claim);
    }
    assertEquals(
        "josé",
        mapper.map(Map.of("iss", "https://idp.example", "sub", "josé", "azp", "app")).subject());
  }

  @Test
  void mapsUserTokenWithNestedRoles() {
    AxizPrincipal p =
        mapper.map(
            Map.of(
                "iss", "https://idp.example/realms/axiz",
                "sub", "user-1",
                "azp", "mobile-app",
                "aud", List.of("payments"),
                "scope", "payments.read payments.write",
                "realm_access", Map.of("roles", List.of("customer")),
                "tenant", "t1"));
    assertEquals("user-1", p.subject());
    assertEquals("mobile-app", p.clientId());
    assertEquals(Set.of("payments.read", "payments.write"), p.scopes());
    assertEquals(Set.of("customer"), p.roles());
    assertEquals("t1", p.tenantId());
    assertFalse(p.isMachine());
    assertTrue(p.hasScope("payments.read"));
    assertTrue(p.hasRole("customer"));
    assertEquals(
        Set.of("SCOPE_payments.read", "SCOPE_payments.write", "ROLE_CUSTOMER"),
        AuthorityMapper.authorities(p));
  }

  @Test
  void mapsMachineTokenAndRejectsIncompleteTokens() {
    AxizPrincipal p =
        mapper.map(Map.of("iss", "i", "sub", "batch-client", "scp", List.of("a", "b"), "aud", "x"));
    assertTrue(p.isMachine());
    assertEquals("batch-client", p.clientId());
    assertEquals("batch-client", p.name());
    assertEquals(List.of("x"), p.audience());
    assertThrows(AccessException.class, () -> mapper.map(Map.of("sub", "s")));
    assertThrows(AccessException.class, () -> mapper.map(Map.of("iss", "i")));
    assertNull(ClaimsMapper.resolve(Map.of("a", "x"), "a.b"));
    ClaimsMapper custom =
        new ClaimsMapper(
            new ClaimMapping(
                List.of("sub"),
                List.of("client_id"),
                List.of("scope"),
                List.of("groups"),
                List.of(),
                List.of("department")));
    AxizPrincipal c =
        custom.map(Map.of("iss", "i", "client_id", "c", "groups", "g1,g2", "department", "ops"));
    assertEquals(Set.of("g1", "g2"), c.roles());
    assertEquals("ops", c.attributes().get("department"));
  }

  /** A Keycloak service account has its own user id in sub: it is still the application. */
  @Test
  void serviceAccountTokensAreMachinesEvenWhenSubIsAUserId() {
    AxizPrincipal keycloak =
        mapper.map(
            Map.of(
                "iss", "i",
                "sub", "5f0c4e8a-service-account-user",
                "azp", "payment-process",
                "preferred_username", "service-account-payment-process"));
    assertTrue(keycloak.isMachine());
    assertEquals("payment-process", keycloak.subject());
    AxizPrincipal entra =
        mapper.map(Map.of("iss", "i", "sub", "object-id", "azp", "app", "idtyp", "app"));
    assertTrue(entra.isMachine());
    AxizPrincipal user =
        mapper.map(
            Map.of(
                "iss", "i", "sub", "C-1001", "azp", "payments-web", "preferred_username", "ana"));
    assertFalse(user.isMachine());
    assertEquals("C-1001", user.subject());
    ClaimsMapper presence =
        new ClaimsMapper(
            new ClaimMapping(
                List.of("sub"),
                List.of("azp"),
                List.of("scope"),
                List.of(),
                List.of(),
                List.of(),
                List.of("client_id")));
    assertTrue(
        presence.map(Map.of("iss", "i", "sub", "u-1", "azp", "c", "client_id", "c")).isMachine());
  }

  /** A username the user may choose never turns an end user into an application. */
  @Test
  void aUsernameThatOnlyLooksLikeAServiceAccountIsAnEndUser() {
    AxizPrincipal user =
        mapper.map(
            Map.of(
                "iss", "i",
                "sub", "C-1001",
                "azp", "payments-web",
                "preferred_username", "service-account-payment-process"));
    assertFalse(user.isMachine());
    assertEquals("C-1001", user.subject());
    AxizPrincipal upperCase =
        mapper.map(
            Map.of(
                "iss", "i",
                "sub", "5f0c4e8a",
                "azp", "Payment-Process",
                "preferred_username", "service-account-payment-process"));
    assertTrue(upperCase.isMachine());
  }

  /** Without the fallback, a token that names no client is rejected, never taken for one. */
  @Test
  void tokensWithoutAClientClaimAreRejectedWhenSubIsNotAcceptedAsClient() {
    ClaimsMapper strict = new ClaimsMapper(ClaimMapping.defaults(), false);
    assertThrows(
        AccessException.class, () -> strict.map(Map.of("iss", "i", "sub", "C-1001", "scope", "x")));
    AxizPrincipal app = strict.map(Map.of("iss", "i", "sub", "batch", "azp", "batch"));
    assertTrue(app.isMachine());
    AxizPrincipal user = strict.map(Map.of("iss", "i", "sub", "C-1001", "azp", "payments-web"));
    assertFalse(user.isMachine());
    // the default keeps accepting sub as the client
    assertTrue(mapper.map(Map.of("iss", "i", "sub", "batch-client")).isMachine());
  }

  @Test
  void issuerPolicyIsAnAllowList() {
    IssuerPolicy policy =
        new IssuerPolicy(
            List.of(
                new TrustedIssuer(
                    "https://idp.example",
                    URI.create("https://idp.example/jwks"),
                    List.of("payments"))));
    assertTrue(policy.isTrusted("https://idp.example"));
    assertFalse(policy.isTrusted("https://evil.example"));
    assertTrue(policy.audienceAccepted("https://idp.example", List.of("other", "payments")));
    assertFalse(policy.audienceAccepted("https://idp.example", List.of("other")));
    assertFalse(policy.audienceAccepted("https://evil.example", List.of("payments")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrustedIssuer("i", URI.create("http://idp.example/jwks"), List.of("a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrustedIssuer("i", URI.create("https://idp.example/jwks"), List.of()));
    new TrustedIssuer("i", URI.create("http://keycloak.idp.svc.cluster.local/jwks"), List.of("a"));
    // without a JWKS URI the keys are discovered from the issuer, which must then use https
    assertTrue(
        new TrustedIssuer("https://sso.example/realms/axiz", null, List.of("a")).discovered());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrustedIssuer("http://sso.example/realms/axiz", null, List.of("a")));
    new TrustedIssuer("http://localhost:8180/realms/axiz", null, List.of("a"));
    assertThrows(IllegalArgumentException.class, () -> new IssuerPolicy(List.of()));
  }

  @Test
  void rateLimiterUsesTokenDerivedKeys() {
    MutableClock clock = new MutableClock();
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
    AxizPrincipal p = mapper.map(Map.of("iss", "i", "sub", "u", "azp", "app"));
    String key = RateLimitKeys.forClient(p, "payments");
    assertTrue(limiter.tryAcquire(key, 2, Duration.ofSeconds(1)).allowed());
    assertEquals(0, limiter.tryAcquire(key, 2, Duration.ofSeconds(1)).remaining());
    RateLimiter.Decision rejected = limiter.tryAcquire(key, 2, Duration.ofSeconds(1));
    assertFalse(rejected.allowed());
    assertTrue(rejected.retryAfter().toMillis() > 0);
    clock.now = clock.now.plusSeconds(1);
    assertTrue(limiter.tryAcquire(key, 2, Duration.ofSeconds(1)).allowed());
    assertTrue(RateLimitKeys.forSubject(p, "g").endsWith(":u"));
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-03-01T10:00:00.500Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
