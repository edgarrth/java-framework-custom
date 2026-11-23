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
 * Configuración del resource server ({@code axiz.security.*}). La lista de emisores permitidos
 * normalmente reside en el ConfigMap {@code axiz-common}.
 *
 * @param issuers emisores de confianza; los tokens de cualquier otro emisor se rechazan antes de
 *     cualquier búsqueda de claves
 * @param algorithms algoritmos JWS aceptados
 * @param clockSkew diferencia de reloj tolerada para {@code exp} y {@code nbf}
 * @param claims asociación de claims con el principal
 * @param publicPaths patrones de ruta que no requieren token (ninguno por defecto)
 * @param publicApiDocs indica si el documento OpenAPI y Swagger UI son accesibles sin token;
 *     desactivado por defecto (producción), el perfil local de los servicios generados lo activa
 * @param publicInfo indica si {@code /actuator/info} es accesible sin token; desactivado por
 *     defecto, ya que los detalles del build y de git ayudan a un atacante; las sondas de salud
 *     siempre son públicas
 * @param actuatorAuthority autoridad requerida para los endpoints del actuator distintos de health
 * @param rateLimit limitación de tasa por invocante
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

  /**
   * Plazos finitos de conexión y de respuesta completa para el descubrimiento y la descarga de
   * claves.
   */
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

  /** Cobertura previa de autorización; warn está pensado solo para desarrollo. */
  public record Authorization(
      @DefaultValue("fail") String unprotected, @DefaultValue List<RouteRule> rules) {
    public Authorization {
      if (!List.of("fail", "warn").contains(unprotected))
        throw new IllegalArgumentException("authorization.unprotected must be fail or warn");
      rules = List.copyOf(rules);
    }
  }

  /**
   * Regla HTTP específica aplicada por la cadena de seguridad efectiva del framework; las
   * autoridades se combinan con AND.
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

  /** Rutas del documento OpenAPI y de Swagger UI (valores por defecto de springdoc). */
  static final List<String> API_DOCS =
      List.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");

  /** Todas las rutas accesibles sin token, además de las sondas de salud. */
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
   * Emisor de confianza. Las claves provienen del URI JWKS configurado (o descubierto), nunca del
   * token.
   *
   * @param issuer valor exacto de {@code iss}
   * @param jwkSetUri endpoint JWKS; cuando está vacío, se descubre a partir de los metadatos OpenID
   *     del emisor con el primer token de ese emisor
   * @param audiences audiencias aceptadas (al menos una)
   * @param profile perfil disjunto de token de acceso para este emisor
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
   * Rutas de claims para cada atributo del principal; los puntos navegan por claims anidados.
   *
   * @param subject claims del subject
   * @param clientId claims del id de cliente
   * @param scopes claims de scopes
   * @param roles claims de roles
   * @param tenant claims del tenant
   * @param attributes claims adicionales copiados al principal
   * @param machine marcadores exactos {@code claim=value} propios del emisor para tokens de
   *     aplicación; se rechazan los claims de perfil de usuario y las heurísticas de presencia y de
   *     prefijo
   * @param subjectAsClient activación opcional para usar sub como cliente solo cuando también
   *     coincide un marcador de máquina; en otro caso, los tokens sin claim de cliente se rechazan
   *     (401)
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

    /** Construcción con nombre; build comprueba los valores obligatorios y las invariantes. */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Estado mutable de construcción; se usa un builder por operación, nunca como estado
     * compartido.
     */
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
   * Limitación de tasa. La clave siempre proviene del token validado (cliente o subject), nunca de
   * una cabecera controlada por el cliente.
   *
   * @param store nombre de la integración de límites de tasa instalada; memory se restringe a un
   *     solo proceso
   * @param enabled indica si se aplican los límites
   * @param rules límites por grupo de rutas
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
   * Regla de límite de tasa.
   *
   * @param name nombre del grupo de rutas
   * @param paths patrones de ruta cubiertos
   * @param limit solicitudes permitidas por ventana
   * @param window duración de la ventana
   * @param key {@code client}, {@code subject} o {@code anonymous-ip} para llamadas anónimas desde
   *     el gateway
   * @param failOpen política de este grupo cuando el almacén no está disponible; obligatoria cuando
   *     está habilitado
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
     * Política explícita; la construcción programática directa también falla de forma cerrada al
     * configurar.
     */
    public boolean failurePolicy() {
      if (failOpen == null)
        throw new IllegalStateException("rate-limit rule " + name + " must declare fail-open");
      return failOpen;
    }
  }

  /** Construcción con nombre; build comprueba los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
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
