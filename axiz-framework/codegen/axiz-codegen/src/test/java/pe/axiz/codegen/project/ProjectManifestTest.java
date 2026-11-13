package pe.axiz.codegen.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectManifestTest {
  private static final String VALID =
      """
      schema: 2
      kind: service
      name: beneficiaries
      groupId: pe.axiz.payments
      basePackage: pe.axiz.payments.beneficiaries
      archetype: business
      structure: layered
      extensions: []
      stack: servlet
      persistence: dynamodb
      axizVersion: 2.0.0
      contract:
        openapi: src/main/openapi/openapi.yaml
        asyncapi: null
      capabilities:
        - foundation
      operations:
        []
      """;

  @Test
  void acceptsOnlyTheCurrentSchema(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve(".axiz"));
    for (String schema : new String[] {"1", "99", "2.5"}) {
      Files.writeString(dir.resolve(ProjectManifest.PATH), "schema: " + schema + "\n");
      assertThrows(IOException.class, () -> ProjectManifest.read(dir));
    }
  }

  @Test
  void readsTheCurrentKeysAndRoundTrips(@TempDir Path dir) throws Exception {
    write(dir, VALID);
    ProjectManifest manifest = ProjectManifest.read(dir);
    assertEquals("beneficiaries", manifest.name());
    manifest.write(dir);
    assertEquals(manifest, ProjectManifest.read(dir));
  }

  @Test
  void rejectsAnyKeyOutsideTheCurrentSchema(@TempDir Path dir) throws Exception {
    for (String extra : new String[] {"framework: hexagonal\n", "stak: reactive\n"}) {
      write(dir, VALID + extra);
      IOException failure = assertThrows(IOException.class, () -> ProjectManifest.read(dir));
      assertTrue(failure.getMessage().startsWith("Unknown key '"), failure.getMessage());
      assertTrue(failure.getMessage().contains("allowed: ["), failure.getMessage());
    }
    write(
        dir, VALID.replace("  asyncapi: null\n", "  asyncapi: null\n  graphql: schema.graphql\n"));
    IOException failure = assertThrows(IOException.class, () -> ProjectManifest.read(dir));
    assertTrue(failure.getMessage().contains("'graphql'"), failure.getMessage());
  }

  @Test
  void rejectsWrongTypesInsteadOfDroppingValues(@TempDir Path dir) throws Exception {
    String[][] cases = {
      {
        "contract:\n  openapi: src/main/openapi/openapi.yaml\n  asyncapi: null\n",
        "contract: invalid\n",
        "'contract'"
      },
      {"capabilities:\n  - foundation\n", "capabilities: foundation\n", "'capabilities'"},
      {
        "capabilities:\n  - foundation\n",
        "capabilities:\n  - {name: foundation}\n",
        "'capabilities'"
      },
      {"operations:\n  []\n", "operations: listBeneficiaries\n", "'operations'"},
      {"extensions: []\n", "extensions: cqrs\n", "'extensions'"},
      {"  openapi: src/main/openapi/openapi.yaml\n", "  openapi: [a, b]\n", "'contract.openapi'"},
      {"kind: service\n", "kind: invalid\n", "'kind'"},
      {"kind: service\n", "kind: platform\n", "does not match"},
      {"persistence: dynamodb\n", "persistence: mongodb\n", "'persistence'"},
      {"stack: servlet\n", "stack: grpc\n", "'stack'"},
      {"name: beneficiaries\n", "name: [beneficiaries]\n", "'name'"},
      {"groupId: pe.axiz.payments\n", "groupId: 123\n", "'groupId'"},
      {
        "basePackage: pe.axiz.payments.beneficiaries\n",
        "basePackage: {bad: value}\n",
        "'basePackage'"
      },
      {"axizVersion: 2.0.0\n", "axizVersion: [2.0.0]\n", "'axizVersion'"},
    };
    for (String[] c : cases) {
      assertTrue(VALID.contains(c[0]), c[0]);
      write(dir, VALID.replace(c[0], c[1]));
      IOException failure = assertThrows(IOException.class, () -> ProjectManifest.read(dir));
      assertTrue(failure.getMessage().contains(c[2]), c[1] + " -> " + failure.getMessage());
    }
  }

  @Test
  void acceptsAPlatformManifestWithoutContract(@TempDir Path dir) throws Exception {
    write(
        dir,
        """
        schema: 2
        kind: platform
        name: axiz-sample-starter
        groupId: pe.axiz.samples
        basePackage: pe.axiz.samples.axizsamplestarter
        archetype: starter
        axizVersion: 2.0.0
        structure: none
        extensions: []
        stack: servlet
        persistence: none
        capabilities: []
        operations: []
        """);
    ProjectManifest manifest = ProjectManifest.read(dir);
    assertTrue(!manifest.isService());
    assertEquals(null, manifest.openapi());
  }

  private static void write(Path dir, String content) throws IOException {
    Files.createDirectories(dir.resolve(".axiz"));
    Files.writeString(dir.resolve(ProjectManifest.PATH), content);
  }
}
