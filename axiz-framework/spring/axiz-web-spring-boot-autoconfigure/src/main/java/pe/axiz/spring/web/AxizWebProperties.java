package pe.axiz.spring.web;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import pe.axiz.core.api.HeaderDefinition;

/**
 * Convenciones web ({@code axiz.web.*}).
 *
 * @param headers catálogo de cabeceras de negocio: validación y lista permitida de propagación
 * @param excludedPaths patrones de ruta (sintaxis {@code PathPattern}) donde se omite la validación
 *     de cabeceras
 * @param channelHeader cabecera que lleva el canal de origen, si el servicio usa uno
 * @param pagination límites de paginación
 * @param apiVersion versionado nativo de la API de Spring
 * @param cors política CORS
 * @param json convenciones de salida JSON
 */
@ConfigurationProperties("axiz.web")
public record AxizWebProperties(
    @DefaultValue List<Header> headers,
    @DefaultValue({
          "/actuator/**",
          "/v3/api-docs/**",
          "/swagger-ui/**",
          "/swagger-ui.html",
          "/error"
        })
        List<String> excludedPaths,
    @Nullable String channelHeader,
    @DefaultValue Pagination pagination,
    @DefaultValue ApiVersion apiVersion,
    @DefaultValue Cors cors,
    @DefaultValue Json json,
    @DefaultValue("1MB") DataSize maxRequestBody,
    @DefaultValue List<BodyLimit> bodyLimits) {
  @ConstructorBinding
  public AxizWebProperties {
    excludedPaths = List.copyOf(excludedPaths);
    headers = List.copyOf(headers);
    bodyLimits = List.copyOf(bodyLimits);
    if (maxRequestBody.toBytes() < 1)
      throw new IllegalArgumentException("max-request-body must be positive");
  }

  /**
   * Una cabecera del catálogo.
   *
   * @param name nombre de la cabecera
   * @param required indica si las solicitudes deben llevarla
   * @param pattern expresión regular de validación
   * @param propagate indica si se reenvía a las llamadas salientes y a los mensajes
   * @param sensitive indica si se enmascara en los logs y la auditoría
   * @param maxLength longitud máxima
   */
  public record Header(
      String name,
      @DefaultValue("false") boolean required,
      @Nullable String pattern,
      @DefaultValue("true") boolean propagate,
      @DefaultValue("false") boolean sensitive,
      @DefaultValue("256") int maxLength) {

    public HeaderDefinition toDefinition() {
      HeaderDefinition d =
          new HeaderDefinition(name, required, null, propagate, sensitive, maxLength);
      return pattern != null ? d.withPattern(pattern) : d;
    }
  }

  /**
   * Límites de paginación.
   *
   * @param defaultSize tamaño de página cuando el cliente no envía ninguno
   * @param maxSize tamaño de página más grande aceptado
   */
  public record Pagination(@DefaultValue("20") int defaultSize, @DefaultValue("200") int maxSize) {}

  /**
   * Versionado de la API mediante una cabecera de la solicitud.
   *
   * @param enabled indica si se configura el versionado
   * @param header cabecera que lleva la versión
   * @param defaultVersion versión asumida cuando la cabecera está ausente; cuando la cabecera es
   *     opcional y esto está vacío, la versión admitida más baja
   * @param supported versiones admitidas
   * @param required indica si la cabecera es obligatoria
   */
  public record ApiVersion(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("API-Version") String header,
      @Nullable String defaultVersion,
      @DefaultValue List<String> supported,
      @DefaultValue("false") boolean required) {

    public ApiVersion {
      supported = List.copyOf(supported);
      if (enabled
          && !required
          && (defaultVersion == null || defaultVersion.isBlank())
          && supported.isEmpty()) {
        throw new IllegalArgumentException(
            "axiz.web.api-version: with an optional header, set default-version or supported");
      }
    }

    /**
     * Versión de una solicitud sin la cabecera. Spring WebFlux no encuentra ningún handler para una
     * solicitud así cuando no hay versión por defecto (todas las rutas responden 404), por lo que
     * una cabecera opcional siempre tiene una: la configurada por defecto o la versión admitida más
     * baja.
     */
    public @Nullable String effectiveDefaultVersion() {
      if (defaultVersion != null && !defaultVersion.isBlank()) {
        return defaultVersion;
      }
      if (required) {
        return null;
      }
      return supported.stream().min(ApiVersion::compareVersions).orElse(null);
    }

    /**
     * Compara versiones numéricas con puntos ("1" < "1.1" < "2" < "10"); el resto del texto se
     * compara como texto.
     */
    static int compareVersions(String a, String b) {
      String[] x = a.split("\\.", -1);
      String[] y = b.split("\\.", -1);
      for (int i = 0; i < Math.max(x.length, y.length); i++) {
        String p = i < x.length && !x[i].isEmpty() ? x[i] : "0";
        String q = i < y.length && !y[i].isEmpty() ? y[i] : "0";
        int c =
            p.chars().allMatch(Character::isDigit) && q.chars().allMatch(Character::isDigit)
                ? Long.compare(Long.parseLong(p), Long.parseLong(q))
                : p.compareTo(q);
        if (c != 0) {
          return c;
        }
      }
      return 0;
    }
  }

  /**
   * Política CORS, desactivada salvo que se declare.
   *
   * @param enabled indica si se registran los mapeos CORS
   * @param pathPattern rutas cubiertas
   * @param allowedOrigins orígenes exactos
   * @param allowedOriginPatterns patrones de origen
   * @param allowedMethods métodos
   * @param allowedHeaders cabeceras de la solicitud
   * @param exposedHeaders cabeceras de la respuesta visibles para el navegador
   * @param allowCredentials indica si se permiten credenciales
   * @param maxAge duración de la caché del pre-flight
   */
  public record Cors(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("/**") String pathPattern,
      @DefaultValue List<String> allowedOrigins,
      @DefaultValue List<String> allowedOriginPatterns,
      @DefaultValue({"GET", "POST", "PUT", "PATCH", "DELETE"}) List<String> allowedMethods,
      @DefaultValue("*") List<String> allowedHeaders,
      @DefaultValue({"X-Correlation-Id", "Location"}) List<String> exposedHeaders,
      @DefaultValue("false") boolean allowCredentials,
      @DefaultValue("30m") Duration maxAge) {
    public Cors {
      allowedHeaders = List.copyOf(allowedHeaders);
      allowedMethods = List.copyOf(allowedMethods);
      allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
      allowedOrigins = List.copyOf(allowedOrigins);
      exposedHeaders = List.copyOf(exposedHeaders);
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
      private boolean enabled = false;
      private @Nullable String pathPattern = null;
      private @Nullable List<String> allowedOrigins = List.of();
      private @Nullable List<String> allowedOriginPatterns = List.of();
      private @Nullable List<String> allowedMethods = List.of();
      private @Nullable List<String> allowedHeaders = List.of();
      private @Nullable List<String> exposedHeaders = List.of();
      private boolean allowCredentials = false;
      private @Nullable Duration maxAge = null;

      private Builder() {}

      public Builder enabled(boolean value) {
        this.enabled = value;
        return this;
      }

      public Builder pathPattern(String value) {
        this.pathPattern = value;
        return this;
      }

      public Builder allowedOrigins(List<String> value) {
        this.allowedOrigins = List.copyOf(value);
        return this;
      }

      public Builder allowedOriginPatterns(List<String> value) {
        this.allowedOriginPatterns = List.copyOf(value);
        return this;
      }

      public Builder allowedMethods(List<String> value) {
        this.allowedMethods = List.copyOf(value);
        return this;
      }

      public Builder allowedHeaders(List<String> value) {
        this.allowedHeaders = List.copyOf(value);
        return this;
      }

      public Builder exposedHeaders(List<String> value) {
        this.exposedHeaders = List.copyOf(value);
        return this;
      }

      public Builder allowCredentials(boolean value) {
        this.allowCredentials = value;
        return this;
      }

      public Builder maxAge(Duration value) {
        this.maxAge = value;
        return this;
      }

      public Cors build() {
        return new Cors(
            enabled,
            Objects.requireNonNull(pathPattern, "pathPattern"),
            Objects.requireNonNull(allowedOrigins, "allowedOrigins"),
            Objects.requireNonNull(allowedOriginPatterns, "allowedOriginPatterns"),
            Objects.requireNonNull(allowedMethods, "allowedMethods"),
            Objects.requireNonNull(allowedHeaders, "allowedHeaders"),
            Objects.requireNonNull(exposedHeaders, "exposedHeaders"),
            allowCredentials,
            Objects.requireNonNull(maxAge, "maxAge"));
      }
    }
  }

  /**
   * Lectura y escritura JSON de la API propia del servicio.
   *
   * @param includeNulls indica si se escriben las propiedades nulas
   * @param maxNumberLength número más largo aceptado
   * @param maxStringLength cadena más larga aceptada
   * @param maxNestingDepth anidamiento más profundo aceptado
   * @param rejectUnknownProperties indica si un cuerpo de solicitud con una propiedad que su tipo
   *     no declara se rechaza (400) en lugar de ignorarse en silencio (OWASP API3, asignación
   *     masiva). Los clientes HTTP y los consumidores de eventos siguen leyendo de forma tolerante
   *     los payloads de terceros.
   */
  public record Json(
      @DefaultValue("false") boolean includeNulls,
      @DefaultValue("100") int maxNumberLength,
      @DefaultValue("1048576") int maxStringLength,
      @DefaultValue("64") int maxNestingDepth,
      @DefaultValue("true") boolean rejectUnknownProperties) {
    @ConstructorBinding
    public Json {
      if (maxNumberLength < 1 || maxStringLength < 1 || maxNestingDepth < 1)
        throw new IllegalArgumentException("JSON read constraints must be positive");
    }

    public Json(boolean includeNulls) {
      this(includeNulls, 100, 1048576, 64, true);
    }
  }

  /**
   * La primera ruta que coincide sobrescribe el tamaño de cuerpo por defecto; el streaming se
   * cuenta sin almacenar en búfer.
   */
  public record BodyLimit(String path, DataSize maxSize) {
    public BodyLimit {
      if (path == null || path.isBlank() || maxSize.toBytes() < 1)
        throw new IllegalArgumentException("body-limits require a path and positive max-size");
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
    private @Nullable List<Header> headers = List.of();
    private @Nullable List<String> excludedPaths = List.of();
    private @Nullable String channelHeader = null;
    private @Nullable Pagination pagination = null;
    private @Nullable ApiVersion apiVersion = null;
    private @Nullable Cors cors = null;
    private @Nullable Json json = null;
    private @Nullable DataSize maxRequestBody = null;
    private @Nullable List<BodyLimit> bodyLimits = List.of();

    private Builder() {}

    public Builder headers(List<Header> value) {
      this.headers = List.copyOf(value);
      return this;
    }

    public Builder excludedPaths(List<String> value) {
      this.excludedPaths = List.copyOf(value);
      return this;
    }

    public Builder channelHeader(@Nullable String value) {
      this.channelHeader = value;
      return this;
    }

    public Builder pagination(Pagination value) {
      this.pagination = value;
      return this;
    }

    public Builder apiVersion(ApiVersion value) {
      this.apiVersion = value;
      return this;
    }

    public Builder cors(Cors value) {
      this.cors = value;
      return this;
    }

    public Builder json(Json value) {
      this.json = value;
      return this;
    }

    public Builder maxRequestBody(DataSize value) {
      this.maxRequestBody = value;
      return this;
    }

    public Builder bodyLimits(List<BodyLimit> value) {
      this.bodyLimits = List.copyOf(value);
      return this;
    }

    public AxizWebProperties build() {
      return new AxizWebProperties(
          Objects.requireNonNull(headers, "headers"),
          Objects.requireNonNull(excludedPaths, "excludedPaths"),
          channelHeader,
          Objects.requireNonNull(pagination, "pagination"),
          Objects.requireNonNull(apiVersion, "apiVersion"),
          Objects.requireNonNull(cors, "cors"),
          Objects.requireNonNull(json, "json"),
          Objects.requireNonNull(maxRequestBody, "maxRequestBody"),
          Objects.requireNonNull(bodyLimits, "bodyLimits"));
    }
  }
}
