package pe.axiz.spring.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import pe.axiz.core.security.ClaimMapping;
import pe.axiz.core.security.ClaimsMapper;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.TrustedIssuer;

class SecurityTest {

  private static final String ISSUER = "https://idp.example.com/realms/axiz";

  private final IssuerPolicy policy =
      new IssuerPolicy(
          List.of(
              new TrustedIssuer(
                  ISSUER,
                  URI.create("https://idp.example.com/realms/axiz/certs"),
                  List.of("payments-api"))));

  private static Jwt jwt(String issuer, List<String> aud) {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .issuer(issuer)
        .audience(aud)
        .subject("user-1")
        .claim("azp", "mobile-app")
        .claim("scope", "payments:write payments:read")
        .claim("roles", List.of("teller"))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }

  @Test
  void converterMapsPrincipalAndAuthorities() {
    AxizAuthentication auth =
        new AxizJwtConverter(new ClaimsMapper(ClaimMapping.defaults()))
            .convert(jwt(ISSUER, List.of("payments-api")));
    assertEquals("user-1", auth.getName());
    assertEquals("mobile-app", auth.axizPrincipal().clientId());
    List<String> authorities =
        auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    assertTrue(authorities.contains("SCOPE_payments:write"));
    assertTrue(authorities.contains("ROLE_TELLER"));
  }

  @Test
  void audienceAndIssuerAreEnforced() {
    AudienceValidator v = new AudienceValidator(policy);
    assertFalse(v.validate(jwt(ISSUER, List.of("payments-api"))).hasErrors());
    assertTrue(v.validate(jwt(ISSUER, List.of("other-api"))).hasErrors());
    TrustedIssuerDecoders decoders =
        new TrustedIssuerDecoders(policy, List.of("RS256"), Duration.ofSeconds(30));
    assertNull(decoders.servlet("https://evil.example.com"));
    assertNull(decoders.reactive("https://evil.example.com"));
  }

  @Test
  void rateLimitKeyComesFromTheToken() {
    AxizAuthentication auth =
        new AxizJwtConverter(new ClaimsMapper(ClaimMapping.defaults()))
            .convert(jwt(ISSUER, List.of("payments-api")));
    RateLimitRules rules =
        new RateLimitRules(
            List.of(
                new AxizSecurityProperties.Rule(
                    "payments",
                    List.of("/payments/**"),
                    10,
                    Duration.ofMinutes(1),
                    "client",
                    null)));
    AxizSecurityProperties.Rule rule = rules.match("/payments/1").orElseThrow();
    assertTrue(RateLimitRules.key(rule, auth.axizPrincipal()).endsWith(":mobile-app"));
    assertTrue(rules.match("/other").isEmpty());
  }

  @Test
  void unauthorizedIsAProblemWithBearerChallenge() throws Exception {
    ProblemAuthenticationEntryPoint ep =
        new ProblemAuthenticationEntryPoint(new SecurityProblems(null));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/payments");
    request.addHeader("Authorization", "Bearer x");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ep.commence(request, response, new BadCredentialsException("expired"));
    assertEquals(401, response.getStatus());
    assertEquals("Bearer error=\"invalid_token\"", response.getHeader("WWW-Authenticate"));
    assertTrue(response.getContentAsString().contains("AXZ-SEC-0401"));
    assertFalse(response.getContentAsString().contains("expired"));
  }

  @Test
  void anEmptyIssuerListFailsWithTheSettingToFix() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> AxizSecurityAutoConfiguration.issuerPolicy(List.of()));
    assertTrue(e.getMessage().startsWith("axiz.security.issuers is empty"));
    assertEquals(
        ISSUER,
        AxizSecurityAutoConfiguration.issuerPolicy(
                List.of(
                    new AxizSecurityProperties.Issuer(
                        ISSUER, null, List.of("payments-api"), JwtAccessProfile.COMPATIBLE)))
            .find(ISSUER)
            .orElseThrow()
            .issuer());
  }
}
