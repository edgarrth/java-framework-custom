package pe.axiz.initializr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Corporate SSO for everything: the web UI signs in with OpenID Connect (session, SPA CSRF cookie);
 * the CLI and automations call the API with their own bearer token (JWT of the same identity
 * provider). Health probes are the only anonymous endpoints.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'";

  @Bean
  SecurityFilterChain initializrSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(Customizer.withDefaults())
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
}
