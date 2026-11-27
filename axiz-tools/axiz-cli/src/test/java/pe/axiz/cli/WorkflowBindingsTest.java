package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.project.ProjectManifest;

class WorkflowBindingsTest {
  @Test
  void bindingsDetectDriftAndRenameKeepsOtherReleaseConfiguration(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    var before = ProjectManifest.read(project);
    var after = before.withName("renamed");
    assertFalse(WorkflowBindings.required(project, before));
    assertTrue(WorkflowBindings.required(project, after));
    Path release = project.resolve("release-please-config.json");
    Files.writeString(
        release, "{\"custom-preserved\":true,\"extra-files\":[\"pom.xml\",\"notes.md\"]}");
    Path notes = project.resolve(".github/workflows/notes.txt");
    Files.writeString(notes, "service: orders");
    assertTrue(WorkflowBindings.rename(project, before, after));
    assertFalse(WorkflowBindings.required(project, after));
    String result = Files.readString(release);
    assertTrue(result.contains("\"custom-preserved\":true"), result);
    assertTrue(result.contains("\"notes.md\""), result);
    assertEquals(1, result.split("pom.xml", -1).length - 1);
    assertEquals("service: orders", Files.readString(notes));
    assertTrue(WorkflowBindings.rename(project, after, after));
    assertEquals(result, Files.readString(release));
    Files.delete(project.resolve(".github/workflows/ci.yaml"));
    assertFalse(WorkflowBindings.required(project, before));
  }

  @Test
  void absentWorkflowDirectoryIsAnAllowedLocalProject(@TempDir Path root) throws Exception {
    Path project = CliHarness.service(root);
    var before = ProjectManifest.read(project);
    Path workflows = project.resolve(".github/workflows");
    try (var files = Files.list(workflows)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(workflows);
    assertTrue(WorkflowBindings.rename(project, before, before.withName("renamed")));
    assertFalse(WorkflowBindings.required(project, before));
  }

  @Test
  void renameKeepsCurrentBuildDeployBindingsAndCommentsConsistent(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    ProjectManifest before = ProjectManifest.read(project);
    ProjectManifest after = before.withName("payment-orders");
    assertTrue(WorkflowBindings.rename(project, before, after));
    String ci = Files.readString(project.resolve(".github/workflows/ci.yaml"));
    String deploy = Files.readString(project.resolve(".github/workflows/deploy.yaml"));
    assertTrue(ci.contains("service: payment-orders"));
    assertTrue(deploy.contains("service: payment-orders"));
    assertTrue(ci.contains("chart: deploy/helm/orders"));
    assertTrue(ci.contains("# Pipeline del servicio"));
    assertTrue(deploy.contains("# Promoción manual"));
  }

  @Test
  void renameCannotChangeUnrelatedSameNameWorkflowParameters(@TempDir Path root) throws Exception {
    Path project = CliHarness.service(root);
    Path ci = project.resolve(".github/workflows/ci.yaml");
    Files.writeString(
        ci,
        Files.readString(ci)
            + "\n  custom:\n    uses: vendor/workflow@abc\n    with:\n      service: orders\n");
    String source = Files.readString(ci);
    ProjectManifest before = ProjectManifest.read(project);
    assertFalse(WorkflowBindings.rename(project, before, before.withName("payment-orders")));
    assertEquals(source, Files.readString(ci));
  }
}
