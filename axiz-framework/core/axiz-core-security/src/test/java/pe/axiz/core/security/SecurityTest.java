package pe.axiz.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.AccessException;

class SecurityTest {
  @Test
  void anonymousBudgetsAreBoundedOpaqueAndIndependentAcrossConnectionsAndRoutes() {
    assertEquals(
        RateLimitKeys.forAnonymous("127.0.0.1", "read"),
        RateLimitKeys.forAnonymous("127.0.0.1", "read"));
    assertNotEquals(
        RateLimitKeys.forAnonymous("127.0.0.1", "read"),
        RateLimitKeys.forAnonymous("127.0.0.2", "read"));
    assertNotEquals(
        RateLimitKeys.forAnonymous("127.0.0.1", "read"),
        RateLimitKeys.forAnonymous("127.0.0.1", "write"));
    assertFalse(RateLimitKeys.forAnonymous("127.0.0.1", "read").contains("127.0.0.1"));
    assertEquals(
        "rl:read:anonymous:".length() + 64,
        RateLimitKeys.forAnonymous("a".repeat(128), "read").length());
    for (String invalid : List.of("", " ", "a".repeat(129)))
      assertThrows(
          IllegalArgumentException.class, () -> RateLimitKeys.forAnonymous(invalid, "read"));
  }

  private final ClaimsMapper mapper = new ClaimsMapper(ClaimMapping.defaults());

  @Test
  void identityClaimsRequireStringsAndWellFormedUnicode() {
    for (String claim : List.of("iss", "sub", "azp", "tenant")) {
      for (Object invalid :
          List.of(List.of("app"), Map.of("id", "app"), true, 123, "app\uD800", "app\uDC00")) {
        var claims =
            new HashMap<String, Object>(
                Map.of(
                    "iss", "https://idp.example", "sub", "user", "azp", "app", "tenant", "bank"));
        claims.put(claim, invalid);
        assertThrows(AccessException.class, () -> mapper.map(claims), claim);
      }
    }
    // Un claim primario mal formado no puede pasar a una alternativa válida.
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
      HashMap<String, Object> claims =
          new HashMap<>(
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
        mapper.map(
            Map.of(
                "iss",
                "i",
                "sub",
                "batch-client",
                "azp",
                "batch-client",
                "scp",
                List.of("a", "b"),
                "aud",
                "x"));
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
                List.of("department"),
                ClaimMapping.DEFAULT_MACHINE_MARKERS));
    AxizPrincipal c =
        custom.map(Map.of("iss", "i", "client_id", "c", "groups", "g1,g2", "department", "ops"));
    assertEquals(Set.of("g1", "g2"), c.roles());
    assertEquals("ops", c.attributes().get("department"));
  }

  private static ClaimsMapper withMachineMarker(String marker) {
    ClaimMapping defaults = ClaimMapping.defaults();
    return new ClaimsMapper(
        new ClaimMapping(
            defaults.subject(),
            defaults.clientId(),
            defaults.scopes(),
            defaults.roles(),
            defaults.tenant(),
            defaults.attributes(),
            List.of(marker)));
  }

  @Test
  void onlyIssuerOwnedExactMarkersIdentifyTechnicalUsers() {
    AxizPrincipal service =
        withMachineMarker("token.kind=service")
            .map(
                Map.of(
                    "iss",
                    "i",
                    "sub",
                    "technical-user-id",
                    "azp",
                    "service-client",
                    "token",
                    Map.of("kind", "service")));
    assertTrue(service.isMachine());
    assertEquals("service-client", service.subject());
    assertTrue(
        mapper
            .map(Map.of("iss", "i", "sub", "object-id", "azp", "app", "idtyp", "app"))
            .isMachine());
    for (Object marker :
        List.of("App", "application", "user", true, 1, List.of("app"), Map.of("value", "app"))) {
      AxizPrincipal user =
          mapper.map(Map.of("iss", "i", "sub", "user-1", "azp", "app", "idtyp", marker));
      assertFalse(user.isMachine());
      assertEquals("user-1", user.subject());
    }
  }

  @Test
  void userProfileNamesNeverChangeIdentityOrAuthorities() {
    for (String username :
        List.of(
            "ana",
            "service-account-payment-process",
            "service-account-payments-web",
            "SERVICE-ACCOUNT-PAYMENTS-WEB")) {
      AxizPrincipal user =
          mapper.map(
              Map.of(
                  "iss",
                  "i",
                  "sub",
                  "C-1001",
                  "azp",
                  "payments-web",
                  "preferred_username",
                  username,
                  "scope",
                  "payments:read",
                  "email",
                  "service-account-payments-web"));
      assertFalse(user.isMachine());
      assertEquals("C-1001", user.subject());
      assertEquals("C-1001", user.name());
      assertEquals("payments-web", user.clientId());
      assertEquals(Set.of("SCOPE_payments:read"), AuthorityMapper.authorities(user));
    }
  }

  @Test
  void unsafeOrAmbiguousMachineMarkerConfigurationFailsEarly() {
    for (String marker :
        List.of(
            "",
            "idtyp",
            "idtyp=",
            "=app",
            "idtyp^=a",
            "preferred_username=x",
            "profile.name=x",
            "email=service@example.test",
            "idtyp= ",
            "idtyp =app")) {
      assertThrows(IllegalArgumentException.class, () -> withMachineMarker(marker), marker);
    }
  }

  @Test
  void subjectAsClientRequiresOptInAndAnIndependentMachineMarker() {
    Map<String, Object> user = Map.of("iss", "i", "sub", "C-1001", "scope", "x");
    ClaimsMapper optIn = new ClaimsMapper(ClaimMapping.defaults(), true);
    assertThrows(AccessException.class, () -> mapper.map(user));
    assertThrows(AccessException.class, () -> optIn.map(user));
    assertThrows(AccessException.class, () -> optIn.map(Map.of("iss", "i", "idtyp", "app")));
    Map<String, Object> machine = Map.of("iss", "i", "sub", "batch-client", "idtyp", "app");
    assertThrows(AccessException.class, () -> mapper.map(machine));
    AxizPrincipal principal = optIn.map(machine);
    assertTrue(principal.isMachine());
    assertEquals("batch-client", principal.clientId());
    assertTrue(mapper.map(Map.of("iss", "i", "sub", "batch", "azp", "batch")).isMachine());
    assertFalse(mapper.map(Map.of("iss", "i", "sub", "C-1001", "azp", "payments-web")).isMachine());
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
    // sin URI de JWKS las claves se descubren a partir del emisor, que entonces debe usar https
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

  @Test
  void collidingJavaIssuerHashesDoNotShareAnyRateLimitBudget() {
    String first = "https://idp.example/Aa";
    String second = "https://idp.example/BB";
    assertEquals(first.hashCode(), second.hashCode());
    var a = mapper.map(Map.of("iss", first, "sub", "user", "azp", "app"));
    var b = mapper.map(Map.of("iss", second, "sub", "user", "azp", "app"));
    assertFalse(
        RateLimitKeys.forClient(a, "payments").equals(RateLimitKeys.forClient(b, "payments")));
    assertFalse(
        RateLimitKeys.forSubject(a, "payments").equals(RateLimitKeys.forSubject(b, "payments")));
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-03-01T10:00:00.500Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
