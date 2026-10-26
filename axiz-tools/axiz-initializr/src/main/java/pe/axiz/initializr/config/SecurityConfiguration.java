package pe.axiz.initializr.config;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Corporate SSO for everything: the web UI signs in with OpenID Connect (session, SPA CSRF cookie);
 * the CLI and automations call the API with their own bearer token (JWT of the same identity
 * provider). Health probes are the only anonymous endpoints.
 *
 * <p>Two OAuth clients are registered, with different roles: {@value #SSO} signs the user in and
 * {@value #GITHUB} only authorizes the creation of repositories for the user already signed in.
 * Only the SSO registration signs in: a GitHub authorization never becomes, or replaces, the
 * identity of the session, even if its code is sent to the login callback by hand. The GitHub
 * callback ({@value #GITHUB_CALLBACK}) is outside the login path and is handled by the OAuth client
 * support, which stores the authorization for the user already signed in.
 *
 * <p>Bearer tokens must come from the SSO issuer and name this API in their audience ({@code
 * axiz.initializr.audience}, {@value #DEFAULT_AUDIENCE} by default): a token of the same identity
 * provider issued for another API is rejected with 401.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  /** Registration that signs users in (corporate identity provider). */
  public static final String SSO = "sso";

  /** Registration used only to create repositories with the user's GitHub authorization. */
  public static final String GITHUB = "github";

  /** Where the browser starts the SSO login: there is no login page with a choice of providers. */
  public static final String SSO_LOGIN = "/oauth2/authorization/" + SSO;

  /** Redirect URI of the GitHub registration, outside the login callback path. */
  public static final String GITHUB_CALLBACK = "/authorized/" + GITHUB;

  /** Audience that bearer tokens must carry when no other is configured. */
  public static final String DEFAULT_AUDIENCE = "axiz-initializr";

  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'";

  @Bean
  SecurityFilterChain initializrSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            a ->
                // only the probes are anonymous: build details (info) need a session or token
                a.requestMatchers("/actuator/health/**").permitAll().anyRequest().authenticated())
        .oauth2Login(
            o ->
                o.loginPage(SSO_LOGIN)
                    .userInfoEndpoint(
                        u ->
                            u.userService(signInOnlyWithSso(new DefaultOAuth2UserService()))
                                .oidcUserService(signInOnlyWithSso(new OidcUserService()))))
        .oauth2Client(Customizer.withDefaults())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .csrf(c -> c.spa())
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.withDefaults().matcher("/v1/**")))
        .headers(
            h ->
                h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                    .referrerPolicy(
                        r ->
                            r.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
    return http.build();
  }

  /**
   * Decoder of the API bearer tokens: signature from the issuer's keys, issuer, validity and
   * audience. The issuer metadata is fetched on the first token, so the service starts even when
   * the identity provider is not reachable yet.
   */
  @Bean
  JwtDecoder apiTokenDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
      InitializrProperties properties) {
    OAuth2TokenValidator<Jwt> validator = apiTokenValidator(issuer, properties.audience());
    return new SupplierJwtDecoder(
        () -> {
          NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
          decoder.setJwtValidator(validator);
          return decoder;
        });
  }

  /**
   * Issuer, validity window (an expiration is required) and audience: what a bearer token needs to
   * reach this API.
   */
  static OAuth2TokenValidator<Jwt> apiTokenValidator(String issuer, String audience) {
    return new DelegatingOAuth2TokenValidator<>(
        new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
  }

  /**
   * Refuses a sign-in through any registration other than {@value #SSO}. Login runs this before
   * creating the session's identity, after the provider answered: the code of another provider
   * (GitHub) sent to the login callback ends in a failed login, never in a session.
   */
  static <R extends OAuth2UserRequest, U extends OAuth2User>
      OAuth2UserService<R, U> signInOnlyWithSso(OAuth2UserService<R, U> delegate) {
    return request -> {
      String registration = request.getClientRegistration().getRegistrationId();
      if (!SSO.equals(registration)) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error(
                "invalid_request", "Sign-in is only possible with the corporate SSO", null));
      }
      return delegate.loadUser(request);
    };
  }
}
