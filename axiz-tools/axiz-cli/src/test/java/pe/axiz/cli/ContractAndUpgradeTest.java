package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.project.ProjectManifest;

/** The answers of {@code axiz contract} and {@code axiz upgrade} beyond the main flow. */
class ContractAndUpgradeTest {

  static final String NEW_OPERATION =
      String.join(
          "\n",
          "  /v1/orders/{orderId}/notes:",
          "    parameters:",
          "      - name: orderId",
          "        in: path",
          "        required: true",
          "        schema: {type: string}",
          "    get:",
          "      operationId: listOrderNotes",
          "      tags: [orders]",
          "      summary: Notes of an order",
          "      responses:",
          "        '200':",
          "          description: Notes",
          "          content:",
          "            application/json:",
          "              schema:",
          "                type: array",
          "                items: {type: string}",
          "              example: []",
          "components:");

  static String withNewOperation(Path contract) throws Exception {
    return Files.readString(contract).replaceFirst("(?m)^components:$", NEW_OPERATION);
  }

  @Test
  void contractAloneShowsItsSubcommands() {
    CliHarness h = new CliHarness();
    assertEquals(0, h.run("contract"));
    assertTrue(h.output().contains("lint") && h.output().contains("diff"), h.output());
  }

  @Test
  void lintReportsErrorsOfAnyFileAndProjectsWithoutContract(@TempDir Path tmp) throws Exception {
    Path broken = tmp.resolve("broken.yaml");
    Files.writeString(
        broken,
        Files.readString(CliHarness.contract(tmp)).replace("      operationId: getOrder\n", ""));
    CliHarness h = new CliHarness();
    assertEquals(ExitCodes.PROBLEMS, h.run("contract", "lint", broken.toString()));
    assertTrue(h.output().contains("✘"), h.output());

    Path library = Files.createDirectories(tmp.resolve("library"));
    ProjectManifest m = ProjectManifest.read(CliHarness.service(tmp));
    ProjectManifest.builder()
        .name(m.name())
        .groupId(m.groupId())
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(m.axizVersion())
        .openapi(null)
        .asyncapi(null)
        .capabilities(m.capabilities())
        .operations(List.of())
        .extensions(List.of())
        .build()
        .write(library);
    CliHarness none = new CliHarness();
    assertEquals(ExitCodes.PROBLEMS, none.run("contract", "lint", "--dir", library.toString()));
    assertTrue(none.output().contains("has no OpenAPI contract"), none.output());
  }

  @Test
  void diffOfEqualAndOfCompatibleContracts(@TempDir Path tmp) throws Exception {
    Path contract = CliHarness.contract(tmp);
    CliHarness same = new CliHarness();
    assertEquals(0, same.run("contract", "diff", contract.toString(), contract.toString()));
    assertTrue(same.output().contains("No changes"), same.output());

    Path added = tmp.resolve("added.yaml");
    Files.writeString(added, withNewOperation(contract));
    CliHarness compatible = new CliHarness();
    assertEquals(0, compatible.run("contract", "diff", contract.toString(), added.toString()));
    assertTrue(compatible.output().contains("operation added"), compatible.output());
    assertFalse(compatible.output().contains("BREAKING"));
  }

  @Test
  void syncCreatesNewOperationsAndRefusesALintFailingContract(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    Path openapi = p.resolve("src/main/openapi/openapi.yaml");
    Files.writeString(openapi, withNewOperation(openapi));

    CliHarness h = new CliHarness();
    h.javaFormatter = false;
    assertEquals(0, h.run("contract", "sync", "--dir", p.toString()), h.output());
    assertTrue(h.output().contains("Created src/main/java/"), h.output());
    assertTrue(h.output().contains("Review and merge "), h.output());
    assertTrue(h.output().contains(".axiz-new (then delete it)"), h.output());
    assertTrue(
        h.processes.commands.stream().anyMatch(command -> command.contains("spotless:apply")));
    assertTrue(ProjectManifest.read(p).operations().contains("listOrderNotes"));

    Files.writeString(
        openapi, Files.readString(openapi).replace("      operationId: getOrder\n", ""));
    CliHarness invalid = new CliHarness();
    assertEquals(ExitCodes.PROBLEMS, invalid.run("contract", "sync", "--dir", p.toString()));
    assertTrue(invalid.output().contains("lint errors: nothing generated"), invalid.output());
    assertTrue(invalid.output().contains("operationId"), invalid.output());
  }

  @Test
  void upgradeToTheCurrentVersionChangesNothing(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    String version = ProjectManifest.read(p).axizVersion();
    CliHarness h = new CliHarness();
    assertEquals(0, h.run("upgrade", "--to", version, "--dir", p.toString()));
    assertTrue(h.output().contains("Already on axiz " + version), h.output());
    assertTrue(h.processes.commands.isEmpty());
  }

  @Test
  void aDryRunRunsTheRecipesInAnIsolatedCopy(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    String pom = Files.readString(p.resolve("pom.xml"));
    CliHarness h = new CliHarness();
    assertEquals(0, h.run("upgrade", "--to", "2.3.0", "--dry-run", "--dir", p.toString()));
    assertEquals(pom, Files.readString(p.resolve("pom.xml")), "nothing is written");
    assertTrue(
        h.processes
            .commands
            .get(0)
            .contains(
                "org.openrewrite.maven:rewrite-maven-plugin:" + Version.rewritePlugin() + ":run"));
    assertTrue(h.output().contains("Dry run done"), h.output());
  }

  @Test
  void versionsOnlyRecipesFailingOrWithoutMaven(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    CliHarness skip = new CliHarness();
    assertEquals(0, skip.run("upgrade", "--to", "2.3.0", "--skip-rewrite", "--dir", p.toString()));
    assertEquals("2.3.0", ProjectManifest.read(p).axizVersion());
    assertTrue(skip.processes.commands.isEmpty());
    assertTrue(
        skip.output()
            .contains("Recipes skipped explicitly; migration validation is still required"),
        skip.output());

    CliHarness failing = new CliHarness();
    failing.processes.exitCode = 3;
    assertEquals(
        ExitCodes.PROBLEMS, failing.run("upgrade", "--to", "2.4.0", "--dir", p.toString()));
    assertTrue(failing.output().contains("OpenRewrite exited with 3"), failing.output());
    assertEquals(
        "2.3.0",
        ProjectManifest.read(p).axizVersion(),
        "failed recipes must not advance the manifest");

    Files.delete(p.resolve("mvnw"));
    Files.deleteIfExists(p.resolve("mvnw.cmd"));
    CliHarness noMaven = new CliHarness();
    noMaven.processes.installed.clear();
    assertEquals(
        ExitCodes.PROBLEMS, noMaven.run("upgrade", "--to", "2.5.0", "--dir", p.toString()));
    assertTrue(noMaven.output().contains("Maven not found"), noMaven.output());
    assertTrue(noMaven.processes.commands.isEmpty());
    assertEquals("2.3.0", ProjectManifest.read(p).axizVersion());
  }

  @Test
  void currentVersionAndSkippedRecipesStillRejectInvalidArchitecture(@TempDir Path tmp)
      throws Exception {
    Path p = CliHarness.service(tmp);
    Path source = p.resolve("src/main/java/pe/axiz/orders/domain/BadDependency.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        """
        package pe.axiz.orders.domain;
        import org.springframework.stereotype.Component;
        @Component class BadDependency {}
        """);
    CliHarness current = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        current.run(
            "upgrade", "--to", ProjectManifest.read(p).axizVersion(), "--dir", p.toString()),
        current.output());
    CliHarness skipped = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        skipped.run("upgrade", "--to", "2.0.1", "--skip-rewrite", "--dir", p.toString()),
        skipped.output());
    assertTrue(current.processes.commands.isEmpty());
    assertTrue(skipped.processes.commands.isEmpty());
  }
}
