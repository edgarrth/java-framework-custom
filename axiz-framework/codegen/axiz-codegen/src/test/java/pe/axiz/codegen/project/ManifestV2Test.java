package pe.axiz.codegen.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.Structure;

class ManifestV2Test {
  private static void legacy(Path dir, String role, String framework) throws Exception {
    Files.createDirectories(dir.resolve(".axiz"));
    Files.writeString(
        dir.resolve(ProjectManifest.PATH),
        "schema: 1\nname: stable-name\ngroupId: pe.axiz.example\nbasePackage: pe.axiz.example.original\narchetype: "
            + role
            + "\nframework: "
            + framework
            + "\nstack: reactive\naxizVersion: 1.0.0\n");
  }

  @Test
  void migratesCqrsAndPreservesTheExplicitPackage(@TempDir Path dir) throws Exception {
    legacy(dir, "business", "cqrs");
    var project = ProjectManifest.read(dir);
    assertEquals(Structure.HEXAGONAL, project.structure());
    assertTrue(project.cqrs());
    project.withAxizVersion("2.0.0").write(dir);
    String text = Files.readString(dir.resolve(ProjectManifest.PATH));
    assertTrue(text.contains("schema: 2"));
    assertFalse(text.contains("framework:"));
    assertEquals("pe.axiz.example.original", ProjectManifest.read(dir).basePackage());
    assertTrue(ProjectManifest.read(dir).cqrs());
  }

  @Test
  void anIntegrationMustChooseItsBusinessBoundaryExplicitly(@TempDir Path dir) throws Exception {
    legacy(dir, "integration", "hexagonal");
    assertThrows(java.io.IOException.class, () -> ProjectManifest.read(dir));
  }

  @Test
  void refusesUnknownManifestVersions(@TempDir Path dir) throws Exception {
    legacy(dir, "business", "hexagonal");
    Path file = dir.resolve(ProjectManifest.PATH);
    Files.writeString(file, Files.readString(file).replace("schema: 1", "schema: 99"));
    assertThrows(java.io.IOException.class, () -> ProjectManifest.read(dir));
  }
}
