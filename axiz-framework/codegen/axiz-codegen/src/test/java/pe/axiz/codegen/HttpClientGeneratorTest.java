package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.Json;
import pe.axiz.codegen.project.ProjectManifest;

class HttpClientGeneratorTest {
  @Test
  void thirdPartyClientRejectsUnsafeParameterNamesInEveryLocation() {
    for (String location : List.of("path", "query", "header")) {
      for (String name :
          List.of("x\") String injected) { } //", "x\nvalue", "x\\u0022", "x\\uuu0022")) {
        String path = location.equals("path") ? "/records/{" + name + "}" : "/records";
        String contract =
            Json.write(
                Map.of(
                    "openapi",
                    "3.1.0",
                    "info",
                    Map.of("x-axiz-archetype", "business"),
                    "paths",
                    Map.of(
                        path,
                        Map.of(
                            "get",
                            Map.of(
                                "operationId",
                                "getRecord",
                                "parameters",
                                List.of(
                                    Map.of(
                                        "name",
                                        name,
                                        "in",
                                        location,
                                        "schema",
                                        Map.of("type", "string"))),
                                "responses",
                                Map.of("204", Map.of("description", "ok")))))));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HttpClientGenerator.generate(
                    project(Structure.HEXAGONAL, Stack.SERVLET), "vendor", contract));
      }
    }
  }

  @Test
  void thirdPartyPathsCommentsAndModelWireNamesStayData() throws Exception {
    for (String text : JavaSourceSafetyTest.COMMENT_INPUTS) {
      String path = "/vendor/\"quoted\"/\\u0022/";
      String property = "wire\"\\value";
      String contract =
          Json.write(
              Map.of(
                  "openapi",
                  "3.1.0",
                  "info",
                  Map.of("x-axiz-archetype", "business", "title", text, "version", text),
                  "paths",
                  Map.of(
                      path,
                      Map.of(
                          "get",
                          Map.of(
                              "operationId",
                              "getRecord",
                              "summary",
                              text,
                              "responses",
                              Map.of("204", Map.of("description", "ok"))))),
                  "components",
                  Map.of(
                      "schemas",
                      Map.of(
                          "Record",
                          Map.of(
                              "type",
                              "object",
                              "properties",
                              Map.of(property, Map.of("type", "string", "description", text))),
                          "State",
                          Map.of("type", "string", "enum", List.of("wire\"\\u0022"))))));
      var generated =
          HttpClientGenerator.generate(
              project(Structure.HEXAGONAL, Stack.SERVLET), "vendor", contract);
      JavaSourceSafetyTest.assertJavaSyntax(generated.files());
      assertTrue(
          file(generated, "VendorClient.java").contains("@GetExchange(" + Json.write(path) + ")"));
      assertTrue(
          file(generated, "model/Record.java")
              .contains("@JsonProperty(" + Json.write(property) + ")"));
    }
  }

  @Test
  void wireEnumValuesKeepTheirSerializedNamesAndUseValidJavaIdentifiers() {
    String source =
        HttpClientGenerator.model(
            "example",
            "State",
            Map.of("type", "string", "enum", List.of("READY", "paid-ok", "1-pending", "---")),
            Map.of());
    assertTrue(source.contains("public enum State"), source);
    assertTrue(source.contains("READY,"), source);
    assertTrue(source.contains("@JsonProperty(\"paid-ok\")\n  PAID_OK,"), source);
    assertTrue(source.contains("@JsonProperty(\"1-pending\")\n  V_1_PENDING,"), source);
    assertTrue(source.contains("@JsonProperty(\"---\")\n  V_\n"), source);
    String plain =
        HttpClientGenerator.model("example", "State", Map.of("enum", List.of("READY")), Map.of());
    assertEquals(
        "package example;\n\n/** State values. */\npublic enum State {\n  READY\n}\n", plain);
    assertNull(HttpClientGenerator.model("example", "Scalar", Map.of("type", "string"), Map.of()));
  }

  @Test
  void rejectsModelNamesThatCollideAfterJavaNormalization() {
    var enumFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HttpClientGenerator.model(
                    "example", "State", Map.of("enum", List.of("paid-ok", "paid_ok")), Map.of()));
    assertTrue(enumFailure.getMessage().contains("duplicate Java names in State"));
    var properties = new LinkedHashMap<String, Object>();
    properties.put("account-id", Map.of("type", "string"));
    properties.put("account_id", Map.of("type", "string"));
    var recordFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HttpClientGenerator.model(
                    "example",
                    "Account",
                    Map.of("type", "object", "properties", properties),
                    Map.of()));
    assertTrue(recordFailure.getMessage().contains("duplicate Java names in Account"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void generatedCollectionsOwnTheirStateAndKeepAbsentValues(@TempDir Path directory)
      throws Exception {
    Map<String, Object> list = Map.of("type", "array", "items", Map.of("type", "string"));
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("required", list);
    properties.put("optional", list);
    properties.put("metadata", Map.of("type", "object"));
    String source =
        HttpClientGenerator.model(
            "example",
            "CollectionsModel",
            Map.of("type", "object", "required", List.of("required"), "properties", properties),
            Map.of());
    Path file = directory.resolve("CollectionsModel.java");
    Files.writeString(file, source);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-proc:none",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                directory.toString(),
                file.toString()));
    try (var loader =
        new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> type = loader.loadClass("example.CollectionsModel");
      var constructor = type.getConstructor(List.class, List.class, Map.class);
      List<String> required = new ArrayList<>(List.of("first"));
      List<String> optional = new ArrayList<>(List.of("second"));
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("nullable", null);
      Object model = constructor.newInstance(required, optional, metadata);
      required.clear();
      optional.clear();
      metadata.clear();
      assertEquals(List.of("first"), type.getMethod("required").invoke(model));
      assertEquals(List.of("second"), type.getMethod("optional").invoke(model));
      Map<String, Object> snapshot = (Map<String, Object>) type.getMethod("metadata").invoke(model);
      assertTrue(snapshot.containsKey("nullable"));
      assertThrows(UnsupportedOperationException.class, () -> snapshot.put("new", "value"));
      List<String> values = (List<String>) type.getMethod("optional").invoke(model);
      assertThrows(UnsupportedOperationException.class, () -> values.add("new"));
      Object absent = constructor.newInstance(List.of(), null, null);
      assertNull(type.getMethod("optional").invoke(absent));
      assertNull(type.getMethod("metadata").invoke(absent));
    }
  }

  private static ProjectManifest project(Structure f, Stack s) {
    return ProjectManifest.builder()
        .name("payments-experience")
        .groupId("pe.axiz.payments")
        .basePackage("pe.axiz.payments.experience")
        .archetype("ux")
        .structure(f)
        .stack(s)
        .persistence("none")
        .axizVersion("2.0.0")
        .openapi(null)
        .asyncapi(null)
        .capabilities(List.of("http-client"))
        .operations(List.of())
        .extensions(List.of())
        .build();
  }

  private static String file(HttpClientGenerator.Result r, String suffix) {
    return r.files().entrySet().stream()
        .filter(e -> e.getKey().endsWith(suffix))
        .findFirst()
        .map(e -> new String(e.getValue(), StandardCharsets.UTF_8))
        .orElseThrow(() -> new AssertionError(suffix + " not in " + r.files().keySet()));
  }

  @Test
  void generatesInterfaceModelsAndGroupRegistration() {
    HttpClientGenerator.Result r =
        HttpClientGenerator.generate(
            project(Structure.HEXAGONAL, Stack.SERVLET), "order-service", Fixtures.orders());
    String client = file(r, "adapter/out/orderservice/OrderServiceClient.java");
    assertTrue(client.contains("@HttpExchange"));
    assertTrue(client.contains("@PostExchange(\"/v1/orders\")"));
    assertTrue(client.contains("Order createOrder("), client);
    assertTrue(
        client.contains(
            "@RequestHeader(name = \"Idempotency-Key\", required = true) String idempotencyKey"),
        client);
    assertTrue(client.contains("@RequestBody CreateOrderRequest body);"), client);
    assertTrue(
        client.contains("@RequestParam(name = \"page\", required = false) @Nullable Integer page"),
        client);
    assertTrue(
        client.contains("void cancelOrder(@PathVariable(\"orderId\") String orderId);"), client);
    assertTrue(client.contains("List<Order> listCustomerOrders("), client);
    String config = file(r, "OrderServiceClientConfiguration.java");
    assertTrue(config.contains("group = \"order-service\""));
    assertTrue(config.contains("ClientType.REST_CLIENT"));
    String request = file(r, "model/CreateOrderRequest.java");
    assertTrue(
        request.contains(
            "public record CreateOrderRequest(String customerId, BigDecimal amount, String"
                + " currency)"),
        request);
    String page = file(r, "model/OrderPage.java");
    assertTrue(page.contains("@Nullable List<Order> items"), page);
    assertTrue(page.contains("@Nullable Long totalElements"), page);
    assertEquals(
        "${ORDER_SERVICE_BASE_URL:http://localhost:8089}",
        r.properties().get("axiz.http-client.clients.order-service.base-url"));
  }

  @Test
  void reactiveClientsReturnMonos() {
    HttpClientGenerator.Result r =
        HttpClientGenerator.generate(
            project(Structure.BFF, Stack.REACTIVE), "order-service", Fixtures.orders());
    String client = file(r, "client/orderservice/OrderServiceClient.java");
    assertTrue(client.contains("Mono<Order> getOrder("), client);
    assertTrue(client.contains("Mono<Void> cancelOrder("), client);
    assertTrue(file(r, "OrderServiceClientConfiguration.java").contains("WEB_CLIENT"));
  }

  @Test
  void validatesTheName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HttpClientGenerator.generate(
                project(Structure.BFF, Stack.SERVLET), "Bad Name", Fixtures.orders()));
  }
}
