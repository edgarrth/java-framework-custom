package pe.axiz.spring.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
    @DefaultValue RateLimit rateLimit,
    @DefaultValue Authorization authorization,
    @DefaultValue Jwks jwks,
    @DefaultValue("false") boolean allowHttpJwksInCluster) {
  @ConstructorBinding
  public AxizSecurityProperties {
    algorithms = List.copyOf(algorithms);
    issuers = List.copyOf(issuers);
    publicPaths = List.copyOf(publicPaths);
  }

  /** Finite connect and complete-response deadlines for discovery and key download. */
  public record Jwks(
      @DefaultValue("2s") Duration connectTimeout, @DefaultValue("3s") Duration readTimeout) {
    public Jwks {
      for (Duration timeout : List.of(connectTimeout, readTimeout)) {
        if (timeout.compareTo(Duration.ofMillis(1)) < 0
            || timeout.compareTo(Duration.ofSeconds(10)) > 0)
          throw new IllegalArgumentException("JWKS deadlines must be between 1ms and 10s");
      }
    }
  }

  /** Prior authorization coverage; warn is intended only for development. */
  public record Authorization(
      @DefaultValue("fail") String unprotected, @DefaultValue List<RouteRule> rules) {
    public Authorization {
      if (!List.of("fail", "warn").contains(unprotected))
        throw new IllegalArgumentException("authorization.unprotected must be fail or warn");
      rules = List.copyOf(rules);
    }
  }

  /**
   * A specific HTTP rule applied by the framework's effective security chain; authorities are AND.
   */
  public record RouteRule(
      @DefaultValue List<String> paths,
      @DefaultValue List<String> methods,
      @DefaultValue List<String> authorities) {
    public RouteRule {
      paths = List.copyOf(paths);
      methods = List.copyOf(methods);
      authorities = List.copyOf(authorities);
      if (paths.isEmpty() || authorities.isEmpty() || authorities.contains("ROLE_ANONYMOUS"))
        throw new IllegalArgumentException(
            "Specific authorization rules require paths and authorities");
      for (String path : paths)
        if (!path.matches("/[A-Za-z0-9_.-]+(?:/.*)?"))
          throw new IllegalArgumentException(
              "An authorization rule must have a literal route prefix");
      for (String method : methods)
        if (!List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")
            .contains(method))
          throw new IllegalArgumentException("Unsupported authorization HTTP method");
    }
  }

  /** Paths of the OpenAPI document and Swagger UI (springdoc defaults). */
  static final List<String> API_DOCS =
      List.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");

  /** Every path reachable without a token, besides the health probes. */
  public List<String> anonymousPaths(String actuatorBase) {
    List<String> out = new ArrayList<>(publicPaths);
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
   * @param profile disjoint access-token profile for this issuer
   */
  public record Issuer(
      String issuer,
      @Nullable String jwkSetUri,
      @DefaultValue List<String> audiences,
      @DefaultValue("compatible") JwtAccessProfile profile) {
    public Issuer {
      audiences = List.copyOf(audiences);
      Objects.requireNonNull(profile);
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
   * @param machine exact issuer-owned {@code claim=value} markers of application tokens; user
   *     profile claims, presence and prefix heuristics are rejected
   * @param subjectAsClient opt-in to use sub as client only when a machine marker also matches;
   *     otherwise tokens without a client claim are rejected (401)
   */
  public record Claims(
      @DefaultValue("sub") List<String> subject,
      @DefaultValue({"azp", "client_id", "cid", "appid"}) List<String> clientId,
      @DefaultValue({"scope", "scp"}) List<String> scopes,
      @DefaultValue({"roles", "realm_access.roles", "groups"}) List<String> roles,
      @DefaultValue({"tenant", "tenant_id", "tid"}) List<String> tenant,
      @DefaultValue List<String> attributes,
      @DefaultValue("idtyp=app") List<String> machine,
      @DefaultValue("false") boolean subjectAsClient) {
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

    /** Named construction; required values and invariants are checked by build. */
    public static Builder builder() {
      return new Builder();
    }

    /** Mutable construction state; use one builder per operation, never as shared state. */
    public static final class Builder {
      private @Nullable List<String> subject = List.of();
      private @Nullable List<String> clientId = List.of();
      private @Nullable List<String> scopes = List.of();
      private @Nullable List<String> roles = List.of();
      private @Nullable List<String> tenant = List.of();
      private @Nullable List<String> attributes = List.of();
      private @Nullable List<String> machine = List.of();
      private boolean subjectAsClient = false;

      private Builder() {}

      public Builder subject(List<String> value) {
        this.subject = List.copyOf(value);
        return this;
      }

      public Builder clientId(List<String> value) {
        this.clientId = List.copyOf(value);
        return this;
      }

      public Builder scopes(List<String> value) {
        this.scopes = List.copyOf(value);
        return this;
      }

      public Builder roles(List<String> value) {
        this.roles = List.copyOf(value);
        return this;
      }

      public Builder tenant(List<String> value) {
        this.tenant = List.copyOf(value);
        return this;
      }

      public Builder attributes(List<String> value) {
        this.attributes = List.copyOf(value);
        return this;
      }

      public Builder machine(List<String> value) {
        this.machine = List.copyOf(value);
        return this;
      }

      public Builder subjectAsClient(boolean value) {
        this.subjectAsClient = value;
        return this;
      }

      public Claims build() {
        return new Claims(
            Objects.requireNonNull(subject, "subject"),
            Objects.requireNonNull(clientId, "clientId"),
            Objects.requireNonNull(scopes, "scopes"),
            Objects.requireNonNull(roles, "roles"),
            Objects.requireNonNull(tenant, "tenant"),
            Objects.requireNonNull(attributes, "attributes"),
            Objects.requireNonNull(machine, "machine"),
            subjectAsClient);
      }
    }
  }

  /**
   * Rate limiting. The key always comes from the validated token (client or subject), never from a
   * client-controlled header.
   *
   * @param store name of the installed rate-limit integration; memory is restricted to one process
   * @param enabled whether limits are enforced
   * @param rules limits by route group
   */
  public record RateLimit(
      @DefaultValue("false") boolean enabled,
      @Nullable String store,
      @DefaultValue List<Rule> rules) {
    public RateLimit {
      rules = List.copyOf(rules);
      if (enabled && (store == null || !store.matches("[A-Za-z][A-Za-z0-9-]{0,31}")))
        throw new IllegalArgumentException(
            "axiz.security.rate-limit.store is required (redis; memory only in local, test or ci)");
      if (enabled && rules.stream().anyMatch(rule -> rule.failOpen() == null)) {
        throw new IllegalArgumentException(
            "axiz.security.rate-limit: each rule must explicitly declare fail-open");
      }
    }
  }

  /**
   * One rate-limit rule.
   *
   * @param name route group name
   * @param paths path patterns covered
   * @param limit requests allowed per window
   * @param window window length
   * @param key {@code client}, {@code subject}, or {@code anonymous-ip} for anonymous gateway calls
   * @param failOpen policy of this group when the store is unavailable; required when enabled
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
      if (!List.of("client", "subject", "anonymous-ip").contains(key))
        throw new IllegalArgumentException("Unsupported rate-limit key");
      if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
        throw new IllegalArgumentException("A bounded rate-limit group name is required");
      if (limit < 1 || window == null || !window.isPositive())
        throw new IllegalArgumentException("Rate-limit quota and window must be positive");
    }

    /**
     * Explicit policy; direct programmatic construction also fails closed at configuration time.
     */
    public boolean failurePolicy() {
      if (failOpen == null)
        throw new IllegalStateException("rate-limit rule " + name + " must declare fail-open");
      return failOpen;
    }
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable List<Issuer> issuers = List.of();
    private @Nullable List<String> algorithms = List.of();
    private @Nullable Duration clockSkew = null;
    private @Nullable Claims claims = null;
    private @Nullable List<String> publicPaths = List.of();
    private boolean publicApiDocs = false;
    private boolean publicInfo = false;
    private @Nullable String actuatorAuthority = null;
    private @Nullable RateLimit rateLimit = null;
    private @Nullable Authorization authorization = null;
    private @Nullable Jwks jwks = null;
    private boolean allowHttpJwksInCluster = false;

    private Builder() {}

    public Builder issuers(List<Issuer> value) {
      this.issuers = List.copyOf(value);
      return this;
    }

    public Builder algorithms(List<String> value) {
      this.algorithms = List.copyOf(value);
      return this;
    }

    public Builder clockSkew(Duration value) {
      this.clockSkew = value;
      return this;
    }

    public Builder claims(Claims value) {
      this.claims = value;
      return this;
    }

    public Builder publicPaths(List<String> value) {
      this.publicPaths = List.copyOf(value);
      return this;
    }

    public Builder publicApiDocs(boolean value) {
      this.publicApiDocs = value;
      return this;
    }

    public Builder publicInfo(boolean value) {
      this.publicInfo = value;
      return this;
    }

    public Builder actuatorAuthority(String value) {
      this.actuatorAuthority = value;
      return this;
    }

    public Builder rateLimit(RateLimit value) {
      this.rateLimit = value;
      return this;
    }

    public Builder authorization(Authorization value) {
      this.authorization = value;
      return this;
    }

    public Builder jwks(Jwks value) {
      this.jwks = value;
      return this;
    }

    public Builder allowHttpJwksInCluster(boolean value) {
      this.allowHttpJwksInCluster = value;
      return this;
    }

    public AxizSecurityProperties build() {
      return new AxizSecurityProperties(
          Objects.requireNonNull(issuers, "issuers"),
          Objects.requireNonNull(algorithms, "algorithms"),
          Objects.requireNonNull(clockSkew, "clockSkew"),
          Objects.requireNonNull(claims, "claims"),
          Objects.requireNonNull(publicPaths, "publicPaths"),
          publicApiDocs,
          publicInfo,
          Objects.requireNonNull(actuatorAuthority, "actuatorAuthority"),
          Objects.requireNonNull(rateLimit, "rateLimit"),
          Objects.requireNonNull(authorization, "authorization"),
          Objects.requireNonNull(jwks, "jwks"),
          allowHttpJwksInCluster);
    }
  }
}
