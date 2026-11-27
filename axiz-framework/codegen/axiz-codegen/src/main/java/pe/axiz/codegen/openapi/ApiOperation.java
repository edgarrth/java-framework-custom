package pe.axiz.codegen.openapi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Una operación del contrato.
 *
 * @param operationId id de la operación (camelCase)
 * @param method método HTTP (en mayúsculas)
 * @param path plantilla de ruta
 * @param tag primera etiqueta (agrupa las operaciones en una interfaz de API)
 * @param summary resumen
 * @param requestSchema nombre del esquema del cuerpo JSON de la solicitud, si existe
 * @param responseSchema nombre del esquema de la respuesta exitosa, si existe
 * @param successStatus código de estado de éxito
 * @param pathParams nombres de los parámetros de ruta
 * @param paged indica si acepta los parámetros de consulta "page" y "size"
 * @param requestExample cuerpo de solicitud de ejemplo (JSON), si se declara
 * @param queryParams parámetros de consulta distintos de "page", "size" y "sort"
 * @param pagingParams cuáles de "page", "size" y "sort" declara el contrato
 * @param headerParams parámetros de cabecera declarados por la operación
 */
public record ApiOperation(
    String operationId,
    String method,
    String path,
    String tag,
    String summary,
    @Nullable String requestSchema,
    @Nullable String responseSchema,
    int successStatus,
    List<String> pathParams,
    boolean paged,
    @Nullable String requestExample,
    List<QueryParam> queryParams,
    List<String> pagingParams,
    List<QueryParam> headerParams,
    List<String> scopes,
    Access access) {

  /**
   * Política explícita de acceso HTTP; las políticas no declaradas o no soportadas no pueden
   * generar un servicio.
   */
  public enum Access {
    SCOPED,
    AUTHENTICATED,
    PUBLIC,
    UNDECLARED,
    UNSUPPORTED
  }

  /**
   * Un parámetro de consulta o de cabecera.
   *
   * @param name nombre del parámetro
   * @param required indica si el contrato lo marca como obligatorio
   */
  public record QueryParam(String name, boolean required) {}

  public ApiOperation {
    Objects.requireNonNull(access);
    pathParams = List.copyOf(pathParams);
    queryParams = List.copyOf(queryParams);
    pagingParams = List.copyOf(pagingParams);
    headerParams = List.copyOf(headerParams);
    scopes = List.copyOf(new LinkedHashSet<>(scopes));
    if ((access == Access.SCOPED) != !scopes.isEmpty())
      throw new IllegalArgumentException(
          "Scoped access requires scopes; other policies must not declare them");
    for (String scope : scopes) {
      if (!scope.matches("[A-Za-z0-9:_./-]{1,128}"))
        throw new IllegalArgumentException("Unsupported security scope");
    }
  }

  public String authorizationExpression() {
    return scopes.stream()
        .map(scope -> "hasAuthority('SCOPE_" + scope + "')")
        .collect(Collectors.joining(" and "));
  }

  public boolean readOnly() {
    return "GET".equals(method) || "HEAD".equals(method);
  }

  /** Construcción con nombre; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable String operationId = null;
    private @Nullable String method = null;
    private @Nullable String path = null;
    private @Nullable String tag = null;
    private @Nullable String summary = null;
    private @Nullable String requestSchema = null;
    private @Nullable String responseSchema = null;
    private int successStatus = 0;
    private @Nullable List<String> pathParams = List.of();
    private boolean paged = false;
    private @Nullable String requestExample = null;
    private @Nullable List<QueryParam> queryParams = List.of();
    private @Nullable List<String> pagingParams = List.of();
    private @Nullable List<QueryParam> headerParams = List.of();
    private @Nullable List<String> scopes = List.of();
    private @Nullable Access access = null;

    private Builder() {}

    public Builder operationId(String value) {
      this.operationId = value;
      return this;
    }

    public Builder method(String value) {
      this.method = value;
      return this;
    }

    public Builder path(String value) {
      this.path = value;
      return this;
    }

    public Builder tag(String value) {
      this.tag = value;
      return this;
    }

    public Builder summary(String value) {
      this.summary = value;
      return this;
    }

    public Builder requestSchema(@Nullable String value) {
      this.requestSchema = value;
      return this;
    }

    public Builder responseSchema(@Nullable String value) {
      this.responseSchema = value;
      return this;
    }

    public Builder successStatus(int value) {
      this.successStatus = value;
      return this;
    }

    public Builder pathParams(List<String> value) {
      this.pathParams = List.copyOf(value);
      return this;
    }

    public Builder paged(boolean value) {
      this.paged = value;
      return this;
    }

    public Builder requestExample(@Nullable String value) {
      this.requestExample = value;
      return this;
    }

    public Builder queryParams(List<QueryParam> value) {
      this.queryParams = List.copyOf(value);
      return this;
    }

    public Builder pagingParams(List<String> value) {
      this.pagingParams = List.copyOf(value);
      return this;
    }

    public Builder headerParams(List<QueryParam> value) {
      this.headerParams = List.copyOf(value);
      return this;
    }

    public Builder scopes(List<String> value) {
      this.scopes = List.copyOf(value);
      return this;
    }

    public Builder access(Access value) {
      this.access = value;
      return this;
    }

    public ApiOperation build() {
      return new ApiOperation(
          Objects.requireNonNull(operationId, "operationId"),
          Objects.requireNonNull(method, "method"),
          Objects.requireNonNull(path, "path"),
          Objects.requireNonNull(tag, "tag"),
          Objects.requireNonNull(summary, "summary"),
          requestSchema,
          responseSchema,
          successStatus,
          Objects.requireNonNull(pathParams, "pathParams"),
          paged,
          requestExample,
          Objects.requireNonNull(queryParams, "queryParams"),
          Objects.requireNonNull(pagingParams, "pagingParams"),
          Objects.requireNonNull(headerParams, "headerParams"),
          Objects.requireNonNull(scopes, "scopes"),
          Objects.requireNonNull(access, "access"));
    }
  }
}
