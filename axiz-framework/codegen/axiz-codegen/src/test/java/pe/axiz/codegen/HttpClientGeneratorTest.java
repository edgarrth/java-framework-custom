package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.project.ProjectManifest;

class HttpClientGeneratorTest {

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
