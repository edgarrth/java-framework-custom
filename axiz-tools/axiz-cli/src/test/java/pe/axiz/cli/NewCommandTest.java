package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.project.ProjectManifest;

class NewCommandTest {

  @Test
  void createsAServiceOffline(@TempDir Path tmp) throws Exception {
    Path project = CliHarness.service(tmp);
    ProjectManifest m = ProjectManifest.read(project);
    assertEquals("pe.axiz.orders", m.groupId(), "group defaults to pe.axiz.<domain>");
    assertEquals(
        "pe.axiz.orders", m.basePackage(), "package defaults to pe.axiz.<domain>.<service>");
    assertEquals("hexagonal", m.structure().id());
    assertTrue(
        Files.exists(
            project.resolve(
                "src/main/java/pe/axiz/orders/application/usecase/CreateOrderUseCase.java")));
  }

  @Test
  void acceptsTheAdrSingularExtensionOption(@TempDir Path tmp) throws Exception {
    Path project = CliHarness.service(tmp, "--extension", "cqrs");
    assertEquals(
        List.of(pe.axiz.codegen.model.Extension.CQRS), ProjectManifest.read(project).extensions());
  }

  @Test
  void restArchetypesNeedAContract(@TempDir Path tmp) {
    CliHarness h = new CliHarness();
    int code = h.run("new", "orders", "--archetype", "business", "--output", tmp.toString());
    assertEquals(ExitCodes.PROBLEMS, code);
    assertTrue(h.output().contains("required"), h.output());
  }

  @Test
  void reportsInvalidCombinations(@TempDir Path tmp) throws Exception {
    CliHarness h = new CliHarness();
    int code =
        h.run(
            "new",
            "orders",
            "--archetype",
            "business",
            "--stack",
            "reactive",
            "--persistence",
            "aurora",
            "--structure",
            "hexagonal",
            "--extensions",
            "cqrs",
            "-c",
            "saga",
            "--openapi",
            CliHarness.contract(tmp).toString(),
            "--output",
            tmp.toString());
    assertEquals(ExitCodes.PROBLEMS, code);
    assertTrue(h.output().contains("saga"), h.output());
  }

  @Test
  void asksForMissingValuesInInteractiveMode(@TempDir Path tmp) throws Exception {
    Path contract = CliHarness.contract(tmp);
    CliHarness h = new CliHarness();
    h.input =
        String.join(
                "\n",
                "beneficiaries",
                "business",
                "layered",
                "",
                "dynamodb",
                "",
                "",
                "",
                "cache",
                contract.toString())
            + "\n";
    int code = h.run("new", "-i", "--output", tmp.toString());
    assertEquals(0, code, h.output());
    ProjectManifest m = ProjectManifest.read(tmp.resolve("beneficiaries"));
    assertEquals("layered", m.structure().id());
    assertEquals("dynamodb", m.persistence());
    assertTrue(m.capabilities().contains("cache"));
  }

  @Test
  void refusesToOverwriteAnExistingProject(@TempDir Path tmp) throws Exception {
    CliHarness.service(tmp);
    CliHarness h = new CliHarness();
    int code =
        h.run(
            "new",
            "orders",
            "--archetype",
            "business",
            "--openapi",
            tmp.resolve("orders.yaml").toString(),
            "--output",
            tmp.toString());
    assertEquals(ExitCodes.PROBLEMS, code);
    assertTrue(h.output().contains("--force"));
  }
}
