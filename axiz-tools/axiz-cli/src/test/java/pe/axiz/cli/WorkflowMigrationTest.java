package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.project.ProjectRename;
import pe.axiz.codegen.render.TemplateSet;

class WorkflowMigrationTest {
  private static ProjectManifest legacy(Path project) throws IOException {
    ProjectManifest manifest = ProjectManifest.read(project).withAxizVersion("1.0.0");
    TemplateSet set = TemplateSet.load(manifest.isService() ? "service" : "library");
    Map<String, Object> context =
        Map.of(
            "project",
            Map.of("name", manifest.name()),
            "api",
            Map.of(
                "present", manifest.openapi() != null, "hasAsyncapi", manifest.asyncapi() != null));
    Files.deleteIfExists(project.resolve(".github/workflows/deploy.yaml"));
    Files.writeString(
        project.resolve(".github/workflows/ci.yaml"),
        set.renderTemplate(
            "/migration/v1/" + (manifest.isService() ? "service" : "library") + "-ci.fixture",
            context));
    if (manifest.isService())
      Files.writeString(
          project.resolve(".github/workflows/cd.yaml"),
          set.renderTemplate("/migration/v1/service-cd.fixture", context));
    try (var input =
        WorkflowMigrationTest.class.getResourceAsStream(
            "/axiz/templates/migration/v1/release.fixture")) {
      Files.writeString(
          project.resolve(".github/workflows/release.yaml"),
          new String(
              java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
    }
    return manifest;
  }

  @Test
  void standardServiceBuildAndDeployAreSeparatedAndSecondPassIsANoop(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    ProjectManifest before = legacy(project);
    assertTrue(WorkflowMigration.required(project));
    assertTrue(WorkflowMigration.migrate(project, before, before.withAxizVersion("2.0.0")));
    assertFalse(WorkflowMigration.required(project));
    assertFalse(Files.exists(project.resolve(".github/workflows/cd.yaml")));
    String ci = Files.readString(project.resolve(".github/workflows/ci.yaml"));
    assertTrue(ci.contains("service-build.yaml@v2.0.0"));
    assertFalse(ci.contains("service-deploy.yaml@"));
    assertTrue(
        Files.readString(project.resolve(".github/workflows/deploy.yaml"))
            .contains("build-run-attempt"));
    assertTrue(
        WorkflowMigration.migrate(
            project, before.withAxizVersion("2.0.0"), before.withAxizVersion("2.0.0")));
    assertEquals(ci, Files.readString(project.resolve(".github/workflows/ci.yaml")));
  }

  @Test
  void customWorkflowChangesOnlyProduceAProposalAndNeverAdvanceTheOriginal(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    legacy(project);
    Path ci = project.resolve(".github/workflows/ci.yaml");
    Files.writeString(
        ci,
        Files.readString(ci)
            + "\n  custom:\n    runs-on: ubuntu-24.04\n    steps:\n      - run: echo custom-check\n");
    String before = Files.readString(ci);
    String manifest = Files.readString(project.resolve(ProjectManifest.PATH));
    CliHarness cli = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        cli.run("upgrade", "--to", "2.0.0", "--skip-rewrite", "--dir", project.toString()),
        cli.output());
    assertEquals(before, Files.readString(ci));
    assertEquals(manifest, Files.readString(project.resolve(ProjectManifest.PATH)));
    assertTrue(cli.output().contains("Customized legacy workflows"));
    assertTrue(
        Files.readString(project.resolve("target/rewrite/rewrite.patch"))
            .contains("service-build.yaml@v2.0.0"));
  }

  @Test
  void renamedServiceKeepsItsChartPathAndRecordedIdentities(@TempDir Path root) throws Exception {
    Path project = CliHarness.service(root);
    ProjectManifest before = legacy(project);
    ProjectRename.apply(project, before, "payment-orders", false);
    ProjectManifest after = before.withName("payment-orders").withAxizVersion("2.0.0");
    assertTrue(WorkflowMigration.migrate(project, before, after));
    assertTrue(
        Files.readString(project.resolve(".github/workflows/ci.yaml"))
            .contains("chart: deploy/helm/orders"));
    assertTrue(
        Files.readString(project.resolve(".github/workflows/ci.yaml"))
            .contains("service: payment-orders"));
    assertTrue(ProjectRename.problems(project).isEmpty());
    assertTrue(
        Files.readString(project.resolve(".axiz/identities.yaml"))
            .contains("originalName: orders"));
  }

  @Test
  void platformLibraryAndStarterUseDraftReleasesForIndependentPromotion(@TempDir Path root)
      throws Exception {
    for (String kind : new String[] {"library", "starter"}) {
      CliHarness cli = new CliHarness();
      Path directory = Files.createDirectory(root.resolve(kind));
      assertEquals(
          0,
          cli.run(
              "new",
              "axiz-geo",
              "--archetype",
              kind,
              "--group",
              "pe.axiz",
              "--package",
              "pe.axiz.core.geo",
              "--output",
              directory.toString()),
          cli.output());
      Path project = directory.resolve("axiz-geo");
      ProjectManifest before = legacy(project);
      assertTrue(WorkflowMigration.migrate(project, before, before.withAxizVersion("2.0.0")));
      assertFalse(WorkflowMigration.required(project));
      assertTrue(
          Files.readString(project.resolve(".github/workflows/ci.yaml"))
              .contains("lib-build.yaml@v2.0.0"));
      Map<?, ?> release =
          SafeYaml.create(1_000_000)
              .load(Files.readString(project.resolve("release-please-config.json")));
      assertEquals(Boolean.TRUE, release.get("draft"));
      assertEquals(Boolean.TRUE, release.get("force-tag-creation"));
    }
  }
}
