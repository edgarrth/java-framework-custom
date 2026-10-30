package pe.axiz.spring.security;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.security.ClaimMapping;

/**
 * Resource server settings ({@code axiz.security.*}). The issuer allow-list normally lives in the
 * {@code axiz-common} ConfigMap.
 *
 * @param issuers trusted issuers; tokens from any other issuer are rejected before any key lookup
 * @param algorithms accepted JWS algorithms
 * @param clockSkew tolerated clock difference for {@code exp} and {@code nbf}
 * @param claims claim mapping to the principal
 * @param publicPaths path patterns that do not require a token (none by default)
 * @param publicApiDocs whether the OpenAPI document and Swagger UI are reachable without a token;
 *     off by default (production), the local profile of generated services turns it on
 * @param publicInfo whether {@code /actuator/info} is reachable without a token; off by default,
 *     since build and git details help an attacker; health probes are always public
 * @param actuatorAuthority authority required for actuator endpoints other than health
 * @param rateLimit rate limiting per caller
 */
@ConfigurationProperties("axiz.security")
public record AxizSecurityProperties(
    @DefaultValue List<Issuer> issuers,
    @DefaultValue({"RS256", "PS256", "ES256"}) List<String> algorithms,
    @DefaultValue("30s") Duration clockSkew,
    @DefaultValue Claims claims,
    @DefaultValue List<String> publicPaths,
    @DefaultValue("false") boolean publicApiDocs,
    @DefaultValue("false") boolean publicInfo,
    @DefaultValue("SCOPE_actuator") String actuatorAuthority,
    @DefaultValue RateLimit rateLimit) {
  public AxizSecurityProperties {
    algorithms = List.copyOf(algorithms);
    issuers = List.copyOf(issuers);
    publicPaths = List.copyOf(publicPaths);
  }

  /** Paths of the OpenAPI document and Swagger UI (springdoc defaults). */
  static final List<String> API_DOCS =
      List.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");

  /** Every path reachable without a token, besides the health probes. */
  public List<String> anonymousPaths(String actuatorBase) {
    List<String> out = new java.util.ArrayList<>(publicPaths);
    if (publicApiDocs) {
      out.addAll(API_DOCS);
    }
    if (publicInfo) {
      out.add(actuatorBase + "/info");
    }
    return List.copyOf(out);
  }

  /**
   * A trusted issuer. Keys come from the configured (or discovered) JWKS URI, never from the token.
   *
   * @param issuer exact {@code iss} value
   * @param jwkSetUri JWKS endpoint; when empty it is discovered from the issuer's OpenID metadata
   *     on the first token of that issuer
   * @param audiences accepted audiences (at least one)
   */
  public record Issuer(
      String issuer, @Nullable String jwkSetUri, @DefaultValue List<String> audiences) {
    public Issuer {
      audiences = List.copyOf(audiences);
    }
  }

  /**
   * Claim paths for each principal attribute; dots navigate nested claims.
   *
   * @param subject subject claims
   * @param clientId client id claims
   * @param scopes scope claims
   * @param roles role claims
   * @param tenant tenant claims
   * @param attributes extra claims copied to the principal
   * @param machine markers of client-credentials tokens whose {@code sub} is a service-account id
   *     ({@code claim}, {@code claim=value} or {@code claim^=prefix}, the prefix followed by the
   *     token's client id): they are mapped with the client as subject, so a service account is
   *     never taken for an end user
   * @param subjectAsClient whether a token without any client claim takes its {@code sub} as the
   *     client and is mapped as an application; set it to false when the provider always sends a
   *     client claim, so an end-user token without one is rejected (401) instead
   */
  public record Claims(
      @DefaultValue("sub") List<String> subject,
      @DefaultValue({"azp", "client_id", "cid", "appid"}) List<String> clientId,
      @DefaultValue({"scope", "scp"}) List<String> scopes,
      @DefaultValue({"roles", "realm_access.roles", "groups"}) List<String> roles,
      @DefaultValue({"tenant", "tenant_id", "tid"}) List<String> tenant,
      @DefaultValue List<String> attributes,
      @DefaultValue({"idtyp=app", "preferred_username^=service-account-"}) List<String> machine,
      @DefaultValue("true") boolean subjectAsClient) {
    public Claims {
      attributes = List.copyOf(attributes);
      clientId = List.copyOf(clientId);
      machine = List.copyOf(machine);
      roles = List.copyOf(roles);
      scopes = List.copyOf(scopes);
      subject = List.copyOf(subject);
      tenant = List.copyOf(tenant);
    }

    public ClaimMapping toMapping() {
      return new ClaimMapping(subject, clientId, scopes, roles, tenant, attributes, machine);
    }
  }

  /**
   * Rate limiting. The key always comes from the validated token (client or subject), never from a
   * client-controlled header.
   *
   * @param enabled whether limits are enforced
   * @param rules limits by route group
   * @param failOpen whether requests pass when the limiter store is unavailable, for rules that do
   *     not decide it themselves: true keeps reads available during a store outage; sensitive route
   *     groups (money movement, callbacks) set {@code fail-open: false} on their rule
   */
  public record RateLimit(
      @DefaultValue("false") boolean enabled,
      @DefaultValue List<Rule> rules,
      @DefaultValue("true") boolean failOpen) {
    public RateLimit {
      rules = List.copyOf(rules);
    }
  }

  /**
   * One rate-limit rule.
   *
   * @param name route group name
   * @param paths path patterns covered
   * @param limit requests allowed per window
   * @param window window length
   * @param key {@code client} or {@code subject}
   * @param failOpen policy of this group when the store is unavailable; empty uses the global one
   */
  public record Rule(
      String name,
      @DefaultValue("/**") List<String> paths,
      @DefaultValue("100") long limit,
      @DefaultValue("1m") Duration window,
      @DefaultValue("client") String key,
      @Nullable Boolean failOpen) {
    public Rule {
      paths = List.copyOf(paths);
    }

    /** The policy of this rule, or the global one. */
    public boolean failOpen(boolean global) {
      return failOpen != null ? failOpen : global;
    }
  }
}
