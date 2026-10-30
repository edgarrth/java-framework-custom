package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.project.ProjectManifest;

class HttpClientGeneratorTest {

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
        javax.tools.ToolProvider.getSystemJavaCompiler()
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
        new URLClassLoader(
            new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
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

  private static ProjectManifest project(Framework f, Stack s) {
    return new ProjectManifest(
        "payments-experience",
        "pe.axiz.payments",
        "pe.axiz.payments.experience",
        "experience",
        f,
        s,
        "none",
        "1.0.0",
        null,
        null,
        List.of("http-client"),
        List.of());
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
            project(Framework.HEXAGONAL, Stack.SERVLET), "order-service", Fixtures.orders());
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
            project(Framework.BFF, Stack.REACTIVE), "order-service", Fixtures.orders());
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
                project(Framework.BFF, Stack.SERVLET), "Bad Name", Fixtures.orders()));
  }
}
