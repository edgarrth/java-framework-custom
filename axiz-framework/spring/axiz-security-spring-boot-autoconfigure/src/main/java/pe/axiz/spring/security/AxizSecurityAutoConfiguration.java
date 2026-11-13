package pe.axiz.spring.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.web.util.pattern.PathPattern;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.errors.ProblemFactory;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.security.ClaimsMapper;
import pe.axiz.core.security.InMemoryAsyncRateLimiter;
import pe.axiz.core.security.InMemoryRateLimiter;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.core.security.TrustedIssuer;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import reactor.core.publisher.Mono;

/**
 * OAuth2 resource server for servlet and reactive services: issuer allow-list with per-issuer JWKS,
 * audience and algorithm checks, claims mapped to {@code AxizPrincipal}, method security, security
 * headers, protected actuator, problem responses for 401/403/429 and optional per-caller rate
 * limits. Services may declare their own {@code SecurityFilterChain} to take over.
 */
@AutoConfiguration(
    afterName = "pe.axiz.spring.errors.AxizErrorsAutoConfiguration",
    beforeName = {
      "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration",
      "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
      "org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.React"
          + "iveOAuth2ResourceServerAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManageme"
          + "ntWebSecurityAutoConfiguration"
    })
@ConditionalOnWebApplication
@ConditionalOnBooleanProperty(name = "axiz.security.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSecurityProperties.class)
@Import(AuthenticatedAccessConfiguration.class)
public class AxizSecurityAutoConfiguration {

  static final String CSP = "default-src 'none'; frame-ancestors 'none'";

  /** A token of an issuer outside the allow-list: rejected before any key lookup (401). */
  static final AuthenticationManager UNTRUSTED_ISSUER =
      authentication -> {
        throw new InvalidBearerTokenException("Invalid issuer");
      };

  @Bean
  @ConditionalOnMissingBean
  IssuerPolicy axizIssuerPolicy(AxizSecurityProperties p) {
    return issuerPolicy(p.issuers());
  }

  /** The allow-list of issuers; a resource server without one would reject every token. */
  static IssuerPolicy issuerPolicy(List<AxizSecurityProperties.Issuer> issuers) {
    if (issuers.isEmpty()) {
      throw new IllegalStateException(
          "axiz.security.issuers is empty: list the trusted token issuers of this service, or"
              + " remove axiz-security-spring-boot-starter if it exposes no API (event processors),"
              + " or set axiz.security.enabled=false");
    }
    List<TrustedIssuer> trusted = new ArrayList<>();
    for (AxizSecurityProperties.Issuer i : issuers) {
      String jwks = i.jwkSetUri();
      trusted.add(
          new TrustedIssuer(
              i.issuer(), jwks == null || jwks.isBlank() ? null : URI.create(jwks), i.audiences()));
    }
    return new IssuerPolicy(trusted);
  }

  @Bean
  @ConditionalOnMissingBean
  ClaimsMapper axizClaimsMapper(AxizSecurityProperties p) {
    return new ClaimsMapper(p.claims().toMapping(), p.claims().subjectAsClient());
  }

  @Bean
  @ConditionalOnMissingBean
  TrustedIssuerDecoders axizTrustedIssuerDecoders(IssuerPolicy policy, AxizSecurityProperties p) {
    return new TrustedIssuerDecoders(policy, p.algorithms(), p.clockSkew());
  }

  @Bean
  @ConditionalOnMissingBean
  AxizJwtConverter axizJwtConverter(ClaimsMapper mapper) {
    return new AxizJwtConverter(mapper);
  }

  @Bean
  @ConditionalOnMissingBean
  SecurityProblems axizSecurityProblems(ObjectProvider<ProblemFactory> factory) {
    return new SecurityProblems(factory.getIfAvailable());
  }

  /** Explicit single-process rate limits, guarded independently of Spring profiles. */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBooleanProperty(name = "axiz.security.rate-limit.enabled")
  @ConditionalOnImperativeApplication
  @ConditionalOnProperty(name = "axiz.security.rate-limit.store", havingValue = "memory")
  RateLimiter axizRateLimiter(ObjectProvider<Clock> clock, Environment env) {
    requireMemoryRateLimits(env);
    return new InMemoryRateLimiter(clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBooleanProperty(name = "axiz.security.rate-limit.enabled")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnProperty(name = "axiz.security.rate-limit.store", havingValue = "memory")
  AsyncRateLimiter axizAsyncRateLimiter(ObjectProvider<Clock> clock, Environment env) {
    requireMemoryRateLimits(env);
    return new InMemoryAsyncRateLimiter(clock.getIfAvailable(Clock::systemUTC));
  }

  private static void requireMemoryRateLimits(Environment env) {
    Environments.requireSingleProcess(
        env.getProperty("axiz.config.environment"),
        "axiz.security.rate-limit.store=memory",
        "redis",
        "limits would apply per replica and reset on every deploy");
  }

  @Bean
  @ConditionalOnBooleanProperty(name = "axiz.security.rate-limit.enabled")
  @ConditionalOnImperativeApplication
  InitializingBean axizRateLimitStoreGuard(ObjectProvider<RateLimiter> limiter) {
    return () -> {
      if (limiter.getIfAvailable() == null)
        throw new IllegalStateException(
            "Selected axiz.security.rate-limit.store is unavailable: add its adapter and connection factory");
    };
  }

  @Bean
  @ConditionalOnBooleanProperty(name = "axiz.security.rate-limit.enabled")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  InitializingBean axizAsyncRateLimitStoreGuard(ObjectProvider<AsyncRateLimiter> limiter) {
    return () -> {
      if (limiter.getIfAvailable() == null)
        throw new IllegalStateException(
            "Selected axiz.security.rate-limit.store is unavailable: add its async adapter and connection factory");
    };
  }

  static String actuatorBase(Environment env) {
    String base = env.getProperty("management.endpoints.web.base-path", "/actuator");
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private static boolean hasAuthorities(Authentication authentication, List<String> authorities) {
    return authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authorities.stream()
            .allMatch(
                required ->
                    authentication.getAuthorities().stream()
                        .anyMatch(granted -> required.equals(granted.getAuthority())));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
  @EnableWebSecurity
  @EnableMethodSecurity
  static class ServletConfiguration {

    @Bean
    ApplicationListener<ApplicationReadyEvent> axizServletAuthorizationCoverage(
        ApplicationContext context, AxizSecurityProperties properties, Environment environment) {
      return event -> {
        if (event.getApplicationContext() != context) return;
        var mappings =
            context
                .getBeanProvider(
                    org.springframework.web.servlet.mvc.method.annotation
                        .RequestMappingHandlerMapping.class)
                .orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().entrySet().stream())
                .map(
                    entry ->
                        new HttpAuthorizationCoverage.Endpoint(
                            entry.getValue(),
                            entry.getKey().getPatternValues(),
                            entry.getKey().getMethodsCondition().getMethods().stream()
                                .map(Enum::name)
                                .collect(Collectors.toSet())))
                .toList();
        var chains = context.getBeanProvider(SecurityFilterChain.class).orderedStream().toList();
        boolean effective =
            chains.size() == 1 && chains.getFirst() instanceof VerifiedServletSecurityChain;
        if (effective
            && context.getBean("springSecurityFilterChain") instanceof FilterChainProxy proxy)
          effective = proxy.getFilterChains().equals(chains);
        else effective = false;
        HttpAuthorizationCoverage.verify(
            context, properties, mappings, false, effective, actuatorBase(environment));
      };
    }

    @Bean
    @ConditionalOnMissingBean
    AuthenticationManagerResolver<HttpServletRequest> axizAuthenticationManagerResolver(
        TrustedIssuerDecoders decoders, AxizJwtConverter converter) {
      AuthenticationManagerResolver<String> byIssuer =
          issuer -> {
            NimbusJwtDecoder decoder = decoders.servlet(issuer);
            if (decoder == null) {
              return UNTRUSTED_ISSUER;
            }
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
            provider.setJwtAuthenticationConverter(converter);
            AuthenticationManager manager = provider::authenticate;
            return manager;
          };
      return new JwtIssuerAuthenticationManagerResolver(byIssuer);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain axizSecurityFilterChain(
        HttpSecurity http,
        Environment env,
        AxizSecurityProperties p,
        AuthenticationManagerResolver<HttpServletRequest> resolver,
        SecurityProblems problems,
        ObjectProvider<RateLimiter> limiter)
        throws Exception {
      String actuator = actuatorBase(env);
      ProblemAuthenticationEntryPoint handlers = new ProblemAuthenticationEntryPoint(problems);
      http.csrf(AbstractHttpConfigurer::disable)
          .httpBasic(AbstractHttpConfigurer::disable)
          .formLogin(AbstractHttpConfigurer::disable)
          .logout(AbstractHttpConfigurer::disable)
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .headers(
              h ->
                  h.contentSecurityPolicy(c -> c.policyDirectives(CSP))
                      .referrerPolicy(
                          r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
          .authorizeHttpRequests(
              a -> {
                a.requestMatchers(actuator + "/health", actuator + "/health/**").permitAll();
                List<String> anonymous = p.anonymousPaths(actuator);
                if (!anonymous.isEmpty()) {
                  a.requestMatchers(anonymous.toArray(String[]::new)).permitAll();
                }
                a.requestMatchers(actuator + "/**").hasAuthority(p.actuatorAuthority());
                for (var rule : p.authorization().rules()) {
                  var manager =
                      (AuthorizationManager<RequestAuthorizationContext>)
                          (authentication, request) ->
                              new AuthorizationDecision(
                                  hasAuthorities(authentication.get(), rule.authorities()));
                  if (rule.methods().isEmpty())
                    a.requestMatchers(rule.paths().toArray(String[]::new)).access(manager);
                  else
                    for (String method : rule.methods())
                      a.requestMatchers(
                              HttpMethod.valueOf(method), rule.paths().toArray(String[]::new))
                          .access(manager);
                }
                a.anyRequest().authenticated();
              })
          .oauth2ResourceServer(
              o ->
                  o.authenticationManagerResolver(resolver)
                      .authenticationEntryPoint(handlers)
                      .accessDeniedHandler(handlers))
          .exceptionHandling(
              e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
          .addFilterAfter(new CallerContextFilter(), BearerTokenAuthenticationFilter.class);
      RateLimiter rl = limiter.getIfAvailable();
      if (p.rateLimit().enabled() && rl != null) {
        http.addFilterAfter(
            new RateLimitFilter(rl, new RateLimitRules(p.rateLimit().rules()), problems),
            CallerContextFilter.class);
      }
      return new VerifiedServletSecurityChain(http.build());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.security.web.server.SecurityWebFilterChain")
  @EnableWebFluxSecurity
  @EnableReactiveMethodSecurity
  static class ReactiveConfiguration {

    @Bean
    ApplicationListener<ApplicationReadyEvent> axizReactiveAuthorizationCoverage(
        ApplicationContext context, AxizSecurityProperties properties, Environment environment) {
      return event -> {
        if (event.getApplicationContext() != context) return;
        var mappings =
            context
                .getBeanProvider(
                    org.springframework.web.reactive.result.method.annotation
                        .RequestMappingHandlerMapping.class)
                .orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().entrySet().stream())
                .map(
                    entry ->
                        new HttpAuthorizationCoverage.Endpoint(
                            entry.getValue(),
                            entry.getKey().getPatternsCondition().getPatterns().stream()
                                .map(PathPattern::getPatternString)
                                .collect(Collectors.toSet()),
                            entry.getKey().getMethodsCondition().getMethods().stream()
                                .map(Enum::name)
                                .collect(Collectors.toSet())))
                .toList();
        var chains = context.getBeanProvider(SecurityWebFilterChain.class).orderedStream().toList();
        boolean effective =
            chains.size() == 1 && chains.getFirst() instanceof VerifiedReactiveSecurityChain;
        HttpAuthorizationCoverage.verify(
            context, properties, mappings, true, effective, actuatorBase(environment));
      };
    }

    @Bean
    @ConditionalOnMissingBean
    JwtIssuerReactiveAuthenticationManagerResolver axizReactiveAuthenticationManagerResolver(
        TrustedIssuerDecoders decoders, AxizJwtConverter converter) {
      return new JwtIssuerReactiveAuthenticationManagerResolver(
          issuer -> {
            NimbusReactiveJwtDecoder decoder = decoders.reactive(issuer);
            if (decoder == null) {
              return Mono.empty();
            }
            JwtReactiveAuthenticationManager manager =
                new JwtReactiveAuthenticationManager(decoder);
            manager.setJwtAuthenticationConverter(
                new ReactiveJwtAuthenticationConverterAdapter(converter::convert));
            return Mono.<ReactiveAuthenticationManager>just(manager);
          });
    }

    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    SecurityWebFilterChain axizSecurityWebFilterChain(
        ServerHttpSecurity http,
        Environment env,
        AxizSecurityProperties p,
        JwtIssuerReactiveAuthenticationManagerResolver resolver,
        SecurityProblems problems,
        ObjectProvider<AsyncRateLimiter> limiter) {
      String actuator = actuatorBase(env);
      ReactiveProblemHandlers handlers = new ReactiveProblemHandlers(problems);
      http.csrf(ServerHttpSecurity.CsrfSpec::disable)
          .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
          .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
          .logout(ServerHttpSecurity.LogoutSpec::disable)
          .headers(
              h ->
                  h.contentSecurityPolicy(c -> c.policyDirectives(CSP))
                      .referrerPolicy(
                          r ->
                              r.policy(
                                  ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy
                                      .NO_REFERRER)))
          .authorizeExchange(
              a -> {
                a.pathMatchers(actuator + "/health", actuator + "/health/**").permitAll();
                List<String> anonymous = p.anonymousPaths(actuator);
                if (!anonymous.isEmpty()) {
                  a.pathMatchers(anonymous.toArray(String[]::new)).permitAll();
                }
                a.pathMatchers(actuator + "/**").hasAuthority(p.actuatorAuthority());
                for (var rule : p.authorization().rules()) {
                  var manager =
                      (ReactiveAuthorizationManager<AuthorizationContext>)
                          (authentication, request) ->
                              authentication
                                  .map(
                                      value ->
                                          (AuthorizationResult)
                                              new AuthorizationDecision(
                                                  hasAuthorities(value, rule.authorities())))
                                  .defaultIfEmpty(new AuthorizationDecision(false));
                  if (rule.methods().isEmpty())
                    a.pathMatchers(rule.paths().toArray(String[]::new)).access(manager);
                  else
                    for (String method : rule.methods())
                      a.pathMatchers(
                              HttpMethod.valueOf(method), rule.paths().toArray(String[]::new))
                          .access(manager);
                }
                a.anyExchange().authenticated();
              })
          .oauth2ResourceServer(
              o ->
                  o.authenticationManagerResolver(resolver)
                      .authenticationEntryPoint(handlers)
                      .accessDeniedHandler(handlers))
          .exceptionHandling(
              e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
          .addFilterAfter(new CallerContextWebFilter(), SecurityWebFiltersOrder.AUTHENTICATION);
      AsyncRateLimiter rl = limiter.getIfAvailable();
      if (p.rateLimit().enabled() && rl != null) {
        http.addFilterAfter(
            new RateLimitWebFilter(rl, new RateLimitRules(p.rateLimit().rules()), problems),
            SecurityWebFiltersOrder.AUTHORIZATION);
      }
      return new VerifiedReactiveSecurityChain(http.build());
    }
  }
}
