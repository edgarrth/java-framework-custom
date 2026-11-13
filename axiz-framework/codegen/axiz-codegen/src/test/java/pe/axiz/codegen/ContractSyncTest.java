package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.ContractDiff;
import pe.axiz.codegen.project.ProjectManifest;

class ContractSyncTest {

  private final ProjectGenerator generator = ProjectGenerator.withDefaults();
  private final ContractSync sync = new ContractSync(generator);

  static final String NEW_OPERATION =
      """
        /v1/orders/{orderId}/refunds:
          post:
            operationId: refundOrder
            tags: [orders]
            summary: Refunds an order
            parameters:
              - {name: orderId, in: path, required: true, schema: {type: string}}
            responses:
              '202':
                description: Accepted
              '409':
                description: Not refundable
                content:
                  application/problem+json:
                    schema: {$ref: '#/components/schemas/Problem'}
      """;

  private Path project(Path dir) throws Exception {
    generator
        .generate(
            Fixtures.request("business", Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of()))
        .writeTo(dir, false);
    return dir;
  }

  @Test
  void cqrsSyncProposesSecurityPoliciesAndTestsWithoutReplacingAuthoredCode(@TempDir Path tmp)
      throws Exception {
    generator
        .generate(
            Fixtures.request(
                "business",
                Structure.HEXAGONAL,
                Stack.SERVLET,
                "aurora",
                List.of(),
                Extension.CQRS))
        .writeTo(tmp, false);
    Path configuration =
        tmp.resolve(
            "src/main/java/pe/axiz/orders/orders/config/CqrsAuthorizationConfiguration.java");
    String authored =
        Files.readString(configuration) + "\n// Application ownership rules stay here.\n";
    Files.writeString(configuration, authored);
    Path contract = tmp.resolve("src/main/openapi/openapi.yaml");
    Files.writeString(
        contract,
        Files.readString(contract).replace("components:\n", NEW_OPERATION + "components:\n"));
    var result = sync.sync(tmp, false);
    assertTrue(result.applied(), result.toString());
    assertEquals(authored, Files.readString(configuration));
    String proposal =
        Files.readString(configuration.resolveSibling(configuration.getFileName() + ".axiz-new"));
    assertTrue(proposal.contains("refundOrderAuthorization"), proposal);
    assertTrue(
        result.proposed().stream()
            .anyMatch(path -> path.endsWith("CqrsAuthorizationTest.java.axiz-new")));
  }

  @Test
  void nothingToDoRightAfterGeneration(@TempDir Path tmp) throws Exception {
    ContractSync.Result r = sync.sync(project(tmp), false);
    assertTrue(r.applied());
    assertTrue(
        r.added().isEmpty()
            && r.removed().isEmpty()
            && r.created().isEmpty()
            && r.proposed().isEmpty(),
        r.toString());
  }

  @Test
  void generatesNewOperationsAndProposesControllers(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp);
    Path contract = dir.resolve("src/main/openapi/openapi.yaml");
    String text = Files.readString(contract);
    Files.writeString(contract, text.replace("components:\n", NEW_OPERATION + "components:\n"));
    ContractSync.Result r = sync.sync(dir, false);
    assertTrue(r.applied(), r.toString());
    assertEquals(List.of("refundOrder"), r.added());
    String base = "src/main/java/pe/axiz/orders/orders/";
    assertTrue(
        r.created().contains(base + "application/usecase/RefundOrderUseCase.java"),
        r.created().toString());
    assertTrue(
        r.proposed()
            .contains(base + "adapter/in/rest/OrdersController.java" + ContractSync.PROPOSAL));
    assertTrue(
        Files.readString(
                dir.resolve(base + "adapter/in/rest/OrdersController.java" + ContractSync.PROPOSAL))
            .contains("refundOrder"));
    assertFalse(
        Files.readString(dir.resolve(base + "adapter/in/rest/OrdersController.java"))
            .contains("refundOrder"),
        "existing code untouched");
    assertTrue(ProjectManifest.read(dir).operations().contains("refundOrder"));
    assertFalse(ContractDiff.hasBreaking(r.changes()));
  }

  @Test
  void blocksBreakingChangesUnlessAllowed(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp);
    Path contract = dir.resolve("src/main/openapi/openapi.yaml");
    Files.writeString(
        contract,
        Files.readString(contract)
            .replace(
                "    delete:\n      operationId: cancelOrder",
                "    x-removed:\n      operationId: cancelOrder"));
    ContractSync.Result blocked = sync.sync(dir, false);
    assertFalse(blocked.applied());
    assertEquals(List.of("cancelOrder"), blocked.removed());
    assertTrue(ContractDiff.hasBreaking(blocked.changes()));
    assertTrue(
        ProjectManifest.read(dir).operations().contains("cancelOrder"), "manifest untouched");
    ContractSync.Result forced = sync.sync(dir, true);
    assertTrue(forced.applied());
    assertFalse(ProjectManifest.read(dir).operations().contains("cancelOrder"));
  }
}
