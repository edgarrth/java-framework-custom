package pe.axiz.codegen.openapi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * One operation of the contract.
 *
 * @param operationId operation id (camelCase)
 * @param method HTTP method (upper case)
 * @param path path template
 * @param tag first tag (groups operations into one API interface)
 * @param summary summary
 * @param requestSchema schema name of the JSON request body, if any
 * @param responseSchema schema name of the success response, if any
 * @param successStatus success status code
 * @param pathParams path parameter names
 * @param paged whether it accepts the "page" and "size" query parameters
 * @param requestExample example request body (JSON), if declared
 * @param queryParams query parameters other than "page", "size" and "sort"
 * @param pagingParams which of "page", "size" and "sort" the contract declares
 * @param headerParams header parameters declared by the operation
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

  /** Explicit HTTP access policy; undeclared/unsupported policies cannot generate a service. */
  public enum Access {
    SCOPED,
    AUTHENTICATED,
    PUBLIC,
    UNDECLARED,
    UNSUPPORTED
  }

  /**
   * A query or header parameter.
   *
   * @param name parameter name
   * @param required whether the contract marks it as required
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

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
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
