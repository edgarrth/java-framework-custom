package pe.axiz.codegen.openapi;

import java.util.List;
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
 * @param paged whether it accepts {@code page}/{@code size} query parameters
 * @param requestExample example request body (JSON), if declared
 * @param queryParams query parameters other than {@code page}, {@code size} and {@code sort}
 * @param pagingParams which of {@code page}, {@code size} and {@code sort} the contract declares
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
    List<QueryParam> headerParams) {

  /**
   * A query or header parameter.
   *
   * @param name parameter name
   * @param required whether the contract marks it as required
   */
  public record QueryParam(String name, boolean required) {}

  public ApiOperation {
    pathParams = List.copyOf(pathParams);
    queryParams = List.copyOf(queryParams);
    pagingParams = List.copyOf(pagingParams);
    headerParams = List.copyOf(headerParams);
  }

  public boolean readOnly() {
    return "GET".equals(method) || "HEAD".equals(method);
  }
}
