package pe.axiz.spring.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;

class VerifiedTokenCallerTest {

  @Test
  void acceptsTheValidatedJwtContractUsedByAxizAuthenticationWithoutAssumingItsConcreteClass() {
    var original =
        jwt(
            Map.of(
                "sub",
                "alice",
                "tenant",
                "tenant-a",
                "client_id",
                "app-a",
                "token_domains",
                List.of("billing")));
    var authentication =
        new AbstractOAuth2TokenAuthenticationToken<Jwt>(
            original.getToken(), original.getAuthorities()) {
          private static final long serialVersionUID = 1L;

          @Override
          public Map<String, Object> getTokenAttributes() {
            return getToken().getClaims();
          }
        };
    authentication.setAuthenticated(true);
    assertEquals("alice", resolver.resolve(authentication).subject());
    authentication.setAuthenticated(false);
    assertThrows(AxizException.class, () -> resolver.resolve(authentication));
  }

  private final VerifiedTokenCaller resolver =
      new VerifiedTokenCaller(
          new AxizTokenizationVaultProperties.Auth(
              "tenant", null, "client_id", "token_domains", "SCOPE_tokens:"));

  private static JwtAuthenticationToken jwt(Map<String, Object> claims) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("synthetic-test-jwt")
            .header("alg", "RS256")
            .claims(values -> values.putAll(claims))
            .build(),
        List.of(new SimpleGrantedAuthority("SCOPE_tokens:lookup")));
  }

  @Test
  void onlyExplicitValidatedJwtClaimsBecomeCallerDimensions() {
    var caller =
        resolver.resolve(
            jwt(
                Map.of(
                    "sub",
                    "alice",
                    "tenant",
                    "tenant-a",
                    "client_id",
                    "app-a",
                    "token_domains",
                    List.of("billing"))));
    assertEquals("alice", caller.subject());
    assertEquals("tenant-a", caller.tenant());
    assertEquals("app-a", caller.clientId());
    assertEquals(Set.of(new TokenDomain("billing")), caller.domains());
    assertEquals("TokenCaller[verified]", caller.toString());
  }

  @Test
  void malformedMissingOrCoercedClaimsAndNonJwtAuthenticationAreDenied() {
    List<Map<String, Object>> cases =
        List.of(
            Map.of(
                "sub",
                "alice",
                "tenant",
                "tenant-a",
                "client_id",
                "app-a",
                "token_domains",
                "billing"),
            Map.of(
                "sub",
                "alice",
                "tenant",
                123,
                "client_id",
                "app-a",
                "token_domains",
                List.of("billing")),
            Map.of("sub", "alice", "client_id", "app-a", "token_domains", List.of("billing")),
            Map.of(
                "sub",
                "alice",
                "tenant",
                "tenant-a",
                "client_id",
                "app-a",
                "token_domains",
                List.of("billing", "billing")));
    for (var claims : cases) {
      var failure = assertThrows(AxizException.class, () -> resolver.resolve(jwt(claims)));
      assertEquals(TokenErrors.DENIED.code(), failure.code());
      assertNull(failure.getCause());
    }
    var other =
        UsernamePasswordAuthenticationToken.authenticated(
            "alice", "synthetic", List.of(new SimpleGrantedAuthority("SCOPE_tokens:lookup")));
    assertThrows(AxizException.class, () -> resolver.resolve(other));
    var unauthenticated = jwt(Map.of("sub", "alice"));
    unauthenticated.setAuthenticated(false);
    assertThrows(AxizException.class, () -> resolver.resolve(unauthenticated));
  }

  @Test
  void singleTenantNamespaceIsExplicitAndCannotAlsoSelectATenantClaim() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizTokenizationVaultProperties.Auth(
                "tenant", "fixed", "client_id", "token_domains", "SCOPE_tokens:"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizTokenizationVaultProperties.Auth(
                null, null, "client_id", "token_domains", "SCOPE_tokens:"));
    var fixed =
        new VerifiedTokenCaller(
            new AxizTokenizationVaultProperties.Auth(
                null, "fixed", "client_id", "token_domains", "SCOPE_tokens:"));
    assertEquals(
        "fixed",
        fixed
            .resolve(
                jwt(
                    Map.of(
                        "sub",
                        "alice",
                        "tenant",
                        "ignored",
                        "client_id",
                        "app-a",
                        "token_domains",
                        List.of("billing"))))
            .tenant());
  }
}
