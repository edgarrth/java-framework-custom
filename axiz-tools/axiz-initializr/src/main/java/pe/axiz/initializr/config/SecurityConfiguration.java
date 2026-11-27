package pe.axiz.initializr.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.util.HttpSessionMutexListener;
import pe.axiz.core.security.RateLimiter;

/**
 * SSO corporativo para todo: la UI web inicia sesión con OpenID Connect (sesión, cookie CSRF para
 * SPA); la CLI y las automatizaciones invocan la API con su propio token bearer (JWT del mismo
 * proveedor de identidad). Las sondas de salud son los únicos endpoints anónimos.
 *
 * <p>Se registran dos clientes OAuth con roles distintos: {@value #SSO} autentica al usuario y
 * {@value #GITHUB} solo autoriza la creación de repositorios para el usuario ya autenticado. Solo
 * el registro SSO inicia sesión: una autorización de GitHub nunca se convierte en la identidad de
 * la sesión ni la reemplaza, aunque su código se envíe a mano al callback de login. El callback de
 * GitHub ({@value #GITHUB_CALLBACK}) está fuera de la ruta de login y lo atiende el soporte de
 * cliente OAuth, que almacena la autorización para el usuario ya autenticado.
 *
 * <p>Los tokens bearer deben provenir del emisor del SSO y nombrar esta API en su audiencia ({@code
 * axiz.initializr.audience}, {@value #DEFAULT_AUDIENCE} por defecto): un token del mismo proveedor
 * de identidad emitido para otra API se rechaza con 401.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InitializrLimits.class)
public class SecurityConfiguration {

  /** Registro que autentica a los usuarios (proveedor de identidad corporativo). */
  public static final String SSO = "sso";

  /** Registro que se usa solo para crear repositorios con la autorización de GitHub del usuario. */
  public static final String GITHUB = "github";

  /**
   * Donde el navegador inicia el login del SSO: no hay una página de login con elección de
   * proveedores.
   */
  public static final String SSO_LOGIN = "/oauth2/authorization/" + SSO;

  /** URI de redirección del registro de GitHub, fuera de la ruta del callback de login. */
  public static final String GITHUB_CALLBACK = "/authorized/" + GITHUB;

  /** Audiencia que deben llevar los tokens bearer cuando no se configura otra. */
  public static final String DEFAULT_AUDIENCE = "axiz-initializr";

  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'";

  @Bean
  ServletListenerRegistrationBean<HttpSessionMutexListener> sessionMutex() {
    return new ServletListenerRegistrationBean<>(new HttpSessionMutexListener());
  }

  @Bean
  EphemeralAuthorizedClients authorizedClients() {
    return new EphemeralAuthorizedClients(Clock.systemUTC());
  }

  @Bean
  SecurityFilterChain initializrSecurity(
      HttpSecurity http,
      EphemeralAuthorizedClients authorizedClients,
      RateLimiter limiter,
      InitializrLimits limits)
      throws Exception {
    http.authorizeHttpRequests(
            a ->
                // solo las sondas son anónimas: los detalles del build (info) requieren sesión o
                // token
                a.requestMatchers("/actuator/health/**").permitAll().anyRequest().authenticated())
        .oauth2Login(
            o ->
                o.authorizedClientRepository(authorizedClients)
                    .loginPage(SSO_LOGIN)
                    .userInfoEndpoint(
                        u ->
                            u.userService(signInOnlyWithSso(new DefaultOAuth2UserService()))
                                .oidcUserService(signInOnlyWithSso(new OidcUserService()))))
        .oauth2Client(o -> o.authorizedClientRepository(authorizedClients))
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
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)));
    http.addFilterAfter(new InitializrQuotaFilter(limiter, limits), AuthorizationFilter.class);
    return http.build();
  }

  /**
   * Decodificador de los tokens bearer de la API: firma con las claves del emisor, emisor, validez
   * y audiencia. Los metadatos del emisor se obtienen con el primer token, de modo que el servicio
   * arranca aunque el proveedor de identidad todavía no sea accesible.
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
   * Emisor, ventana de validez (se exige una expiración) y audiencia: lo que un token bearer
   * necesita para llegar a esta API.
   */
  static OAuth2TokenValidator<Jwt> apiTokenValidator(String issuer, String audience) {
    return new DelegatingOAuth2TokenValidator<>(
        new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
  }

  /**
   * Rechaza un inicio de sesión mediante cualquier registro distinto de {@value #SSO}. El login
   * ejecuta esto previo a crear la identidad de la sesión, una vez que el proveedor respondió: el
   * código de otro proveedor (GitHub) enviado al callback de login termina en un login fallido,
   * nunca en una sesión.
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
