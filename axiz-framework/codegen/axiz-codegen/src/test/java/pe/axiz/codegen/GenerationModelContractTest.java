package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.ApiOperation;

class GenerationModelContractTest {
  private static final Catalog CATALOG = Catalog.load();

  @Test
  void generatedConfigurationOnlyActivatesSelectedProvidersAndLocalInsecureTransports() {
    for (Stack stack : Stack.values()) {
      var request = Fixtures.request("business", Structure.HEXAGONAL, stack, "none", List.of());
      var empty = configuration(request, List.of());
      assertEquals(
          stack == Stack.REACTIVE ? "reactive" : "servlet",
          value(empty, "applicationYaml", "spring.main.web-application-type"));
      assertEquals(
          stack == Stack.SERVLET ? Boolean.TRUE : null,
          value(empty, "applicationYaml", "spring.threads.virtual.enabled"));
      assertEquals(null, value(empty, "applicationYaml", "server.port"));
      assertEquals(null, value(empty, "applicationYaml", "axiz.aws.region"));
      assertEquals(null, value(empty, "applicationYaml", "axiz.messaging.type-prefix"));
      assertEquals(null, value(empty, "localYaml", "spring.datasource.url"));
      assertEquals(null, value(empty, "localYaml", "spring.r2dbc.url"));
      assertEquals(null, value(empty, "localYaml", "spring.kafka.security.protocol"));
      assertEquals(null, value(empty, "localYaml", "spring.data.redis.ssl.enabled"));
      assertEquals(
          null, value(empty, "applicationYaml", "axiz.transport.allow-insecure-data-stores"));
      assertEquals(true, value(empty, "localYaml", "axiz.transport.allow-insecure-data-stores"));
      assertEquals(false, empty.get("hasSecrets"));
      assertEquals(false, empty.get("hasConfigMapKeys"));

      var web = configuration(request, List.of("web"));
      assertEquals(8080, value(web, "applicationYaml", "server.port"));
      assertEquals("graceful", value(web, "applicationYaml", "server.shutdown"));
      var aws = configuration(request, List.of("storage-s3"));
      assertEquals("${AWS_REGION:us-east-1}", value(aws, "applicationYaml", "axiz.aws.region"));
      var kafka = configuration(request, List.of("messaging-kafka"));
      assertEquals("PLAINTEXT", value(kafka, "localYaml", "spring.kafka.security.protocol"));
      assertEquals(
          request.groupId() + ".", value(kafka, "applicationYaml", "axiz.messaging.type-prefix"));
      var redis = configuration(request, List.of("cache-redis"));
      assertEquals(false, value(redis, "localYaml", "spring.data.redis.ssl.enabled"));
      assertEquals(null, value(redis, "localYaml", "spring.kafka.security.protocol"));
      var persistence =
          Fixtures.request("business", Structure.HEXAGONAL, stack, "aurora", List.of());
      var sql = configuration(persistence, List.of(stack == Stack.SERVLET ? "jpa" : "r2dbc"));
      if (stack == Stack.SERVLET) {
        assertEquals(
            "jdbc:postgresql://localhost:5432/orders",
            value(sql, "localYaml", "spring.datasource.url"));
        assertEquals(
            "disable",
            value(sql, "localYaml", "spring.datasource.hikari.data-source-properties.sslmode"));
        assertEquals("disable", value(sql, "localYaml", "spring.flyway.jdbc-properties.sslmode"));
      } else {
        assertEquals(
            "r2dbc:postgresql://localhost:5432/orders",
            value(sql, "localYaml", "spring.r2dbc.url"));
        assertEquals("disable", value(sql, "localYaml", "spring.r2dbc.properties.sslMode"));
      }
    }
  }

  private static Map<String, Object> configuration(ProjectRequest request, List<String> names) {
    return ProjectGenerator.configuration(
        request,
        CATALOG.archetype(request.archetype()).orElseThrow(),
        CATALOG.resolve(names, Catalog.store(request.persistence(), request.stack())),
        Set.of(),
        Set.of());
  }

  private static Object value(Map<String, Object> config, String file, String path) {
    Object current =
        new Yaml(new SafeConstructor(new LoaderOptions())).load((String) config.get(file));
    for (String part : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) return null;
      current = map.get(part);
    }
    return current;
  }

  private static ApiOperation.Builder operation(String method, String path) {
    return ApiOperation.builder()
        .operationId("manageOrders")
        .method(method)
        .path(path)
        .tag("orders")
        .summary("A safe */ description")
        .successStatus(200)
        .access(ApiOperation.Access.AUTHENTICATED);
  }

  @Test
  void emptyPublicOperationsNeverAcquireInputModelsOrAuthorities() {
    var result =
        ProjectGenerator.operation(
            operation("HEAD", "/health").access(ApiOperation.Access.PUBLIC).build(), "com.example");
    for (String flag :
        List.of(
            "write",
            "requiresScopes",
            "authenticatedAccess",
            "hasRequest",
            "hasResponse",
            "hasModel",
            "hasResponseModel",
            "responseIsList",
            "usesList",
            "created",
            "paged",
            "hasPathParams",
            "hasQueryParams",
            "optionalQuery",
            "hasInput",
            "hasExample",
            "exampleTestable")) assertEquals(false, result.get(flag), flag);
    assertEquals(true, result.get("publicEndpoint"));
    assertEquals(true, result.get("readOnly"));
    assertEquals(true, result.get("noContent"));
    assertEquals("ManageOrdersQuery", result.get("policyRequestType"));
    assertEquals("SCOPE_unrelated", result.get("partialAuthority"));
    assertEquals("", result.get("requiredAuthorities"));
    assertEquals("void", result.get("returnType"));
    assertEquals("Void", result.get("requestType"));
    assertEquals("Void", result.get("responseType"));
    assertEquals("/health", result.get("testUri"));
    assertEquals(List.of(), result.get("models"));
    assertEquals("", result.get("inputArguments"));
    assertEquals("", result.get("exampleJson"));
    assertEquals("A safe * / description", result.get("summary"));
  }

  @Test
  void scopedListRequestsPreserveModelMappingPagingAndAllRequiredAuthorities() {
    var op =
        operation("POST", "/accounts/{accountId}/orders/{orderId}")
            .requestSchema("List<CreateOrder>")
            .responseSchema("List<OrderView>")
            .successStatus(201)
            .pathParams(List.of("accountId", "orderId"))
            .queryParams(
                List.of(
                    new ApiOperation.QueryParam("source", true),
                    new ApiOperation.QueryParam("filter", false)))
            .paged(true)
            .requestExample("[{\"name\":\"synthetic\"}]")
            .scopes(List.of("orders:write", "orders:approve"))
            .access(ApiOperation.Access.SCOPED)
            .build();
    var result = ProjectGenerator.operation(op, "com.example");
    for (String flag :
        List.of(
            "write",
            "requiresScopes",
            "hasRequest",
            "hasResponse",
            "hasModel",
            "hasResponseModel",
            "responseIsList",
            "usesList",
            "created",
            "paged",
            "hasPathParams",
            "hasQueryParams",
            "optionalQuery",
            "hasInput",
            "hasExample")) assertEquals(true, result.get(flag), flag);
    for (String flag :
        List.of(
            "readOnly", "publicEndpoint", "authenticatedAccess", "noContent", "exampleTestable"))
      assertEquals(false, result.get(flag), flag);
    assertEquals("ManageOrdersCommand", result.get("policyRequestType"));
    assertEquals("SCOPE_orders:write", result.get("partialAuthority"));
    assertEquals(
        "\"SCOPE_orders:write\", \"SCOPE_orders:approve\"", result.get("requiredAuthorities"));
    assertEquals(
        "hasAuthority('SCOPE_orders:write') and hasAuthority('SCOPE_orders:approve')",
        result.get("authorizationExpression"));
    assertEquals("List<OrderView>", result.get("returnType"));
    assertEquals("OrderView", result.get("responseModel"));
    assertEquals(
        List.of(Map.of("name", "CreateOrder"), Map.of("name", "OrderView")), result.get("models"));
    assertEquals(
        List.of(
            Map.of("name", "accountId", "javaName", "accountId", "last", false),
            Map.of("name", "orderId", "javaName", "orderId", "last", true)),
        result.get("pathParams"));
    assertEquals("/accounts/test/orders/test?source=test&filter=test", result.get("testUri"));
    assertEquals("accountId, orderId, source, filter, page, body", result.get("inputArguments"));
    assertEquals(
        "accountId, orderId, source, filter, page, "
            + "mapper.convertValue(body, "
            + "new TypeReference<java.util.List<com.example.application.model.CreateOrder>>() {})",
        result.get("hexagonalArguments"));
    assertTrue(
        ((String) result.get("controllerParams"))
            .contains("required = false) @Nullable String filter"));
    assertTrue(((String) result.get("inputComponents")).contains("@Nullable String filter"));
    assertFalse(((String) result.get("controllerParams")).contains("@Nullable String source"));
  }
}
