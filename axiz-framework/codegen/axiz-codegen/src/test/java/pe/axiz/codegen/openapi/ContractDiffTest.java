package pe.axiz.codegen.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.Fixtures;

class ContractDiffTest {

  private static OpenApiDocument contract(String request, String response) {
    return OpenApiDocument.parse(
        """
        openapi: 3.1.0
        info: {title: Contract compatibility, version: 1.0.0}
        paths:
          /items/{id}:
            parameters:
              - {in: path, name: id, required: true, schema: {type: string}}
            post:
              operationId: updateItem
              requestBody:
                content:
                  application/json:
                    schema: %s
              responses:
                '200':
                  description: Current value
                  content:
                    application/json:
                      schema: %s
        """
            .formatted(request, response));
  }

  @Test
  void nestedInputRequirementsAndFormatChangesIdentifyTheAffectedConsumerField() {
    var old =
        contract(
            "{type: object, properties: {customer: {type: object, properties: {name: {type: string}}}}}",
            "{type: object, properties: {id: {type: string, format: uuid}}}");
    var now =
        contract(
            "{type: object, properties: {customer: {type: object, required: [name], "
                + "properties: {name: {type: integer}}}}}",
            "{type: object, properties: {id: {type: string, format: date}}}");
    assertEquals(
        List.of(
            new ContractDiff.Change(
                true, "POST /items/{} request.customer.name", "field became required"),
            new ContractDiff.Change(
                true,
                "POST /items/{} request.customer.name",
                "type changed from string to integer"),
            new ContractDiff.Change(
                true, "POST /items/{} response.id", "format changed from uuid to date")),
        ContractDiff.compare(old, now));
  }

  @Test
  void responseArraysAndClosedEnumsMustRemainCompatibleForExistingConsumers() {
    var old = contract("{}", "{type: array, items: {type: string, enum: [OPEN, CLOSED]}}");
    for (String values : List.of("[OPEN]", "[OPEN, CLOSED, CANCELLED]")) {
      var now = contract("{}", "{type: array, items: {type: string, enum: " + values + "}}");
      assertEquals(
          List.of(
              new ContractDiff.Change(
                  true,
                  "POST /items/{} response[]",
                  "enum values changed (consumers may not know new values)")),
          ContractDiff.compare(old, now));
    }
    assertTrue(ContractDiff.compare(old, old).isEmpty());
    var before = contract("{}", "{type: object, properties: {id: {type: string}}}");
    var after =
        contract("{}", "{type: object, properties: {id: {type: string}, label: {type: string}}}");
    assertEquals(
        List.of(
            new ContractDiff.Change(
                false, "POST /items/{} response.label", "response field added")),
        ContractDiff.compare(before, after));
  }

  @Test
  void newRequiredQueryAndBodyAreBreakingWhileRenamedPathVariablesAreCompatible() {
    String before =
        """
        openapi: 3.1.0
        info: {title: Compatibility, version: 1.0.0}
        paths:
          /items/{id}:
            parameters:
              - {in: path, name: id, required: true, schema: {type: string}}
            post:
              operationId: updateItem
              responses:
                '204': {description: Updated}
        """;
    var original = OpenApiDocument.parse(before);
    assertTrue(
        ContractDiff.compare(
                original,
                OpenApiDocument.parse(
                    before.replace("{id}", "{key}").replace("name: id", "name: key")))
            .isEmpty());
    String next =
        before.replace(
            "operationId: updateItem",
            """
        operationId: updateItem
              parameters:
                - {in: query, name: approval, required: true, schema: {type: string}}
              requestBody:
                required: true
                content:
                  application/json:
                    schema: {type: object, properties: {label: {type: string}}}
        """
                .stripTrailing());
    assertEquals(
        List.of(
            new ContractDiff.Change(
                true, "POST /items/{} query:approval", "required parameter added"),
            new ContractDiff.Change(true, "POST /items/{} request", "required request body added")),
        ContractDiff.compare(original, OpenApiDocument.parse(next)));
  }

  private final OpenApiDocument base = OpenApiDocument.parse(Fixtures.orders());

  private List<ContractDiff.Change> diff(String from, String to) {
    return ContractDiff.compare(base, OpenApiDocument.parse(Fixtures.orders().replace(from, to)));
  }

  @Test
  void sameContractHasNoChanges() {
    assertTrue(ContractDiff.compare(base, base).isEmpty());
  }

  @Test
  void addingAnOptionalQueryParameterIsCompatible() {
    List<ContractDiff.Change> changes =
        diff(
            "        - name: status\n",
            "        - name: channel\n"
                + "          in: query\n"
                + "          schema: {type: string}\n"
                + "        - name: status\n");
    assertFalse(ContractDiff.hasBreaking(changes), changes.toString());
  }

  @Test
  void removingAnOperationBreaks() {
    List<ContractDiff.Change> changes =
        diff(
            "    delete:\n      operationId: cancelOrder",
            "    x-delete:\n      operationId: cancelOrder");
    assertTrue(ContractDiff.hasBreaking(changes), changes.toString());
  }

  @Test
  void newRequiredRequestFieldBreaks() {
    List<ContractDiff.Change> changes =
        diff(
            "required: [customerId, amount, currency]",
            "required: [customerId, amount, currency, channel]");
    assertTrue(
        changes.stream().anyMatch(c -> c.breaking() && c.location().endsWith("request.channel")),
        changes.toString());
  }

  @Test
  void removedOrRetypedResponseFieldBreaks() {
    List<ContractDiff.Change> removed =
        diff("        status: {type: string}\n    OrderPage", "    OrderPage");
    assertTrue(
        removed.stream()
            .anyMatch(c -> c.breaking() && c.message().equals("response field removed")),
        removed.toString());
    List<ContractDiff.Change> retyped =
        diff(
            "        amount: {type: number, minimum: 0.01, maximum: 9999999999999999.99,"
                + " multipleOf: 0.01}\n        currency: {type: string}\n        status",
            "        amount: {type: string}\n        currency: {type: string}\n        status");
    assertTrue(
        retyped.stream().anyMatch(c -> c.breaking() && c.message().startsWith("type changed")),
        retyped.toString());
  }

  @Test
  void addingAnOperationIsCompatible() {
    String extra =
        "  /v1/orders/{orderId}/notes:\n"
            + "    get:\n"
            + "      operationId: listNotes\n"
            + "      responses:\n"
            + "        '204': {description: none}\n";
    List<ContractDiff.Change> changes = diff("components:\n", extra + "components:\n");
    assertTrue(
        changes.stream().anyMatch(c -> !c.breaking() && c.message().equals("operation added")));
    assertFalse(ContractDiff.hasBreaking(changes));
  }
}
