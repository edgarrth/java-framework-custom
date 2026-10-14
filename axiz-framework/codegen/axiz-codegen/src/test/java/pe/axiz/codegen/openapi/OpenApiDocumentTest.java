package pe.axiz.codegen.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.Fixtures;

class OpenApiDocumentTest {

  private final OpenApiDocument doc = OpenApiDocument.parse(Fixtures.orders());

  private ApiOperation op(String id) {
    return doc.operations().stream()
        .filter(o -> o.operationId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void readsOperationsWithTheirShape() {
    assertEquals(
        List.of("listOrders", "createOrder", "getOrder", "cancelOrder", "listCustomerOrders"),
        doc.operations().stream().map(ApiOperation::operationId).toList());
    assertEquals("Orders API", doc.title());
    assertEquals(List.of("orders", "customers"), doc.tags());

    ApiOperation list = op("listOrders");
    assertTrue(list.paged(), "page and size come from $ref parameters");
    assertEquals(List.of(new ApiOperation.QueryParam("status", false)), list.queryParams());
    assertEquals("OrderPage", list.responseSchema());

    ApiOperation create = op("createOrder");
    assertEquals(201, create.successStatus());
    assertEquals("CreateOrderRequest", create.requestSchema());
    assertTrue(create.requestExample().contains("\"customerId\":\"c-1\""));

    ApiOperation get = op("getOrder");
    assertEquals(
        List.of("orderId"), get.pathParams(), "path-level parameters apply to every method");
    assertTrue(get.readOnly());

    ApiOperation cancel = op("cancelOrder");
    assertEquals(204, cancel.successStatus());
    assertNull(cancel.responseSchema());

    ApiOperation byCustomer = op("listCustomerOrders");
    assertEquals("List<Order>", byCustomer.responseSchema());
    assertEquals(List.of(new ApiOperation.QueryParam("from", true)), byCustomer.queryParams());
  }

  @Test
  void neverFollowsRemoteReferences() {
    Map<String, Object> remote = Map.of("$ref", "https://evil.example/schema.yaml#/x");
    assertTrue(OpenApiDocument.deref(doc.raw(), remote).isEmpty());
    assertFalse(
        OpenApiDocument.deref(doc.raw(), Map.of("$ref", "#/components/schemas/Order")).isEmpty());
  }

  @Test
  void rejectsWhatIsNotAnOpenApi3Document() {
    assertThrows(
        IllegalArgumentException.class, () -> OpenApiDocument.parse("swagger: '2.0'\npaths: {}"));
    assertThrows(IllegalArgumentException.class, () -> OpenApiDocument.parse("- a\n- b"));
    assertThrows(IllegalArgumentException.class, () -> OpenApiDocument.parse("openapi: [unclosed"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OpenApiDocument.parse("x".repeat(OpenApiDocument.MAX_SIZE + 1)));
  }

  @Test
  void rejectsYamlBombs() {
    StringBuilder b = new StringBuilder("openapi: 3.1.0\na: &a [x, x, x, x, x, x, x, x, x]\n");
    String prev = "a";
    for (int i = 0; i < 12; i++) {
      String n = "l" + i;
      b.append(n)
          .append(": &")
          .append(n)
          .append(" [*")
          .append(prev)
          .append(", *")
          .append(prev)
          .append(", *")
          .append(prev)
          .append(", *")
          .append(prev)
          .append("]\n");
      prev = n;
    }
    assertThrows(IllegalArgumentException.class, () -> OpenApiDocument.parse(b.toString()));
  }
}
