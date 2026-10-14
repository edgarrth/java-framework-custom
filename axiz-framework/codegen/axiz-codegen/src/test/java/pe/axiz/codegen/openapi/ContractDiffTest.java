package pe.axiz.codegen.openapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.Fixtures;

class ContractDiffTest {

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
            "        amount: {type: number}\n        currency: {type: string}\n        status",
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
