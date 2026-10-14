package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
        "pe.axiz.orders.business",
        m.basePackage(),
        "package defaults to pe.axiz.<domain>.<service>");
    assertEquals("hexagonal", m.framework().id());
    assertTrue(
        Files.exists(
            project.resolve(
                "src/main/java/pe/axiz/orders/business/application/usecase/CreateOrderUseCase.java")));
  }

  @Test
  void restArchetypesNeedAContract(@TempDir Path tmp) {
    CliHarness h = new CliHarness();
    int code =
        h.run("new", "orders-business", "--archetype", "business", "--output", tmp.toString());
    assertEquals(ExitCodes.PROBLEMS, code);
    assertTrue(h.output().contains("required"), h.output());
  }

  @Test
  void reportsInvalidCombinations(@TempDir Path tmp) throws Exception {
    CliHarness h = new CliHarness();
    int code =
        h.run(
            "new",
            "orders-business",
            "--archetype",
            "business",
            "--stack",
            "reactive",
            "--persistence",
            "aurora",
            "--framework",
            "cqrs",
            "-c",
            "saga",
            "--openapi",
            CliHarness.contract(tmp).toString(),
            "--output",
            tmp.toString());
    assertEquals(ExitCodes.PROBLEMS, code);
    assertTrue(h.output().contains("need JPA, which blocks"), h.output());
  }

  @Test
  void asksForMissingValuesInInteractiveMode(@TempDir Path tmp) throws Exception {
    Path contract = CliHarness.contract(tmp);
    CliHarness h = new CliHarness();
    h.input =
        String.join(
                "\n",
                "beneficiaries-crud",
                "crud",
                "",
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
    ProjectManifest m = ProjectManifest.read(tmp.resolve("beneficiaries-crud"));
    assertEquals("layered", m.framework().id());
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
            "orders-business",
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
