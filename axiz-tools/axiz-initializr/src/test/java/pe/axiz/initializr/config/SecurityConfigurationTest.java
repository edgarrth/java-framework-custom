package pe.axiz.initializr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class SecurityConfigurationTest {

  private static final String ISSUER = "https://sso.example.test/realms/axiz";

  private final OAuth2AccessToken token =
      new OAuth2AccessToken(
          OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(), Instant.now().plusSeconds(60));

  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> guard =
      SecurityConfiguration.signInOnlyWithSso(
          request -> new DefaultOAuth2User(List.of(), Map.of("sub", "ana"), "sub"));

  @Test
  void signsInWithTheSso() {
    ClientRegistration sso =
        ClientRegistration.withRegistrationId(SecurityConfiguration.SSO)
            .clientId("axiz-initializr")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://sso.example.test/auth")
            .tokenUri("https://sso.example.test/token")
            .build();
    assertThat(guard.loadUser(new OAuth2UserRequest(sso, token)).getName()).isEqualTo("ana");
  }

  @Test
  void gitHubNeverSignsAnybodyIn() {
    ClientRegistration github =
        CommonOAuth2Provider.GITHUB
            .getBuilder(SecurityConfiguration.GITHUB)
            .clientId("github-client")
            .build();
    assertThatThrownBy(() -> guard.loadUser(new OAuth2UserRequest(github, token)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("corporate SSO");
  }

  @Test
  void acceptsOnlyBearerTokensIssuedForThisApi() throws Exception {
    SecretKey key =
        new SecretKeySpec(
            "a-test-only-key-of-at-least-256-bits!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
    decoder.setJwtValidator(SecurityConfiguration.apiTokenValidator(ISSUER, "axiz-initializr"));

    assertThat(decoder.decode(token(key, ISSUER, List.of("axiz-initializr"))).getSubject())
        .isEqualTo("ana");
    assertThat(decoder.decode(token(key, ISSUER, List.of("account", "axiz-initializr"))))
        .isNotNull();

    // same identity provider and signature, but issued for another API: rejected
    assertThatThrownBy(() -> decoder.decode(token(key, ISSUER, List.of("payments-api"))))
        .isInstanceOf(JwtValidationException.class)
        .hasMessageContaining("aud");
    assertThatThrownBy(() -> decoder.decode(token(key, ISSUER, List.of())))
        .isInstanceOf(JwtValidationException.class);
    // the right audience from another issuer: rejected too
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(key, "https://other.example.test", List.of("axiz-initializr"))))
        .isInstanceOf(JwtValidationException.class)
        .hasMessageContaining("iss");
  }

  private static String token(SecretKey key, String issuer, List<String> audience)
      throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .subject("ana")
            .issuer(issuer)
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    if (!audience.isEmpty()) {
      claims.audience(audience);
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
    jwt.sign(new MACSigner(key));
    return jwt.serialize();
  }
}
