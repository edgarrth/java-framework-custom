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
  void renameKeepsCurrentBuildDeployBindingsAndSecurityCommentsConsistent(@TempDir Path root)
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
    assertTrue(ci.contains("# nosemgrep:"));
    assertTrue(deploy.contains("# nosemgrep:"));
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
