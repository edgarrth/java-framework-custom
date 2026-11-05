package pe.axiz.spring.web;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import pe.axiz.core.commons.HeaderDefinition;

/**
 * Web conventions ({@code axiz.web.*}).
 *
 * @param headers business header catalog: validation and propagation allow-list
 * @param excludedPaths path patterns ({@code PathPattern} syntax) where header validation is
 *     skipped
 * @param channelHeader header that carries the origin channel, if the service uses one
 * @param pagination paging limits
 * @param apiVersion native Spring API versioning
 * @param cors CORS policy
 * @param json JSON output conventions
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
   * One header of the catalog.
   *
   * @param name header name
   * @param required whether requests must carry it
   * @param pattern validation regex
   * @param propagate whether it is forwarded to outbound calls and messages
   * @param sensitive whether it is masked in logs and audit
   * @param maxLength maximum length
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
   * Paging limits.
   *
   * @param defaultSize page size when the client sends none
   * @param maxSize largest accepted page size
   */
  public record Pagination(@DefaultValue("20") int defaultSize, @DefaultValue("200") int maxSize) {}

  /**
   * API versioning through a request header.
   *
   * @param enabled whether versioning is configured
   * @param header header carrying the version
   * @param defaultVersion version assumed when the header is absent; when the header is optional
   *     and this is empty, the lowest supported version
   * @param supported supported versions
   * @param required whether the header is mandatory
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
     * Version of a request without the header. Spring WebFlux finds no handler for such a request
     * when there is no default version (every route answers 404), so an optional header always has
     * one: the configured default or the lowest supported version.
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

    /** Compares dotted numeric versions ("1" < "1.1" < "2" < "10"); other text compares as text. */
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
   * CORS policy, disabled unless declared.
   *
   * @param enabled whether CORS mappings are registered
   * @param pathPattern paths covered
   * @param allowedOrigins exact origins
   * @param allowedOriginPatterns origin patterns
   * @param allowedMethods methods
   * @param allowedHeaders request headers
   * @param exposedHeaders response headers visible to the browser
   * @param allowCredentials whether credentials are allowed
   * @param maxAge pre-flight cache duration
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
  }

  /**
   * JSON output.
   *
   * @param includeNulls whether null properties are written
   */
  public record Json(
      @DefaultValue("false") boolean includeNulls,
      @DefaultValue("100") int maxNumberLength,
      @DefaultValue("1048576") int maxStringLength,
      @DefaultValue("64") int maxNestingDepth) {
    @ConstructorBinding
    public Json {
      if (maxNumberLength < 1 || maxStringLength < 1 || maxNestingDepth < 1)
        throw new IllegalArgumentException("JSON read constraints must be positive");
    }

    public Json(boolean includeNulls) {
      this(includeNulls, 100, 1048576, 64);
    }
  }

  /**
   * First matching path overrides the default body size; streaming is counted without buffering.
   */
  public record BodyLimit(String path, DataSize maxSize) {
    public BodyLimit {
      if (path == null || path.isBlank() || maxSize.toBytes() < 1)
        throw new IllegalArgumentException("body-limits require a path and positive max-size");
    }
  }

  public AxizWebProperties(
      List<Header> headers,
      List<String> excludedPaths,
      String channelHeader,
      Pagination pagination,
      ApiVersion apiVersion,
      Cors cors,
      Json json) {
    this(
        headers,
        excludedPaths,
        channelHeader,
        pagination,
        apiVersion,
        cors,
        json,
        DataSize.ofMegabytes(1),
        List.of());
  }
}
