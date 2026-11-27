package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pe.axiz.codegen.project.ProjectManifest;

class CapabilityAdaptersTest {
  @ParameterizedTest
  @CsvSource({
    "servlet, axiz-outbox-jdbc-spring-boot-starter",
    "reactive, axiz-outbox-r2dbc-spring-boot-starter"
  })
  void currentVersionUpgradeAddsTheMissingRuntimeAdapterAndTheNextUpgradeIsANoop(
      String stack, String adapter, @TempDir Path directory) throws Exception {
    Path project =
        CliHarness.service(
            directory,
            "--stack",
            stack,
            "--persistence",
            "aurora",
            "--capability",
            "outbox",
            "--capability",
            "messaging-kafka");
    Path file = project.resolve("pom.xml");
    String pom = Files.readString(file);
    String missing =
        pom.replaceAll(
            "(?s)\\s*<dependency>\\s*<groupId>pe.axiz</groupId>"
                + "\\s*<artifactId>"
                + adapter
                + "</artifactId>.*?</dependency>",
            "");
    assertFalse(pom.equals(missing));
    Files.writeString(file, missing);
    var upgrade = new CliHarness();
    assertEquals(
        0,
        upgrade.run("upgrade", "--to", "2.0.0", "--skip-rewrite", "--dir", project.toString()),
        upgrade.output());
    String aligned = Files.readString(file);
    assertTrue(aligned.contains("<artifactId>" + adapter + "</artifactId>"), aligned);
    var again = new CliHarness();
    assertEquals(
        0, again.run("upgrade", "--to", "2.0.0", "--dir", project.toString()), again.output());
    assertTrue(again.output().contains("Already on axiz"));
    assertTrue(again.processes.commands.isEmpty());
    assertEquals(aligned, Files.readString(file));
  }

  @Test
  void projectAdaptersAreCorrectedWithoutChangingProfilesOrPureCoreDependencies(
      @TempDir Path directory) throws Exception {
    Path project =
        CliHarness.service(
            directory,
            "--persistence",
            "aurora",
            "--capability",
            "outbox",
            "--capability",
            "messaging-kafka");
    var manifest = ProjectManifest.read(project);
    String input =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <dependencies>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-core-commons</artifactId></dependency>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-outbox-jdbc-spring-boot-starter</artifactId>
              <scope>runtime</scope></dependency>
          </dependencies>
          <profiles><profile><id>custom</id><dependencies>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-outbox-jdbc-spring-boot-starter</artifactId>
              <scope>test</scope></dependency>
          </dependencies></profile></profiles>
        </project>
        """;
    String output = CapabilityAdapters.align(input, manifest);
    assertTrue(output.contains("<artifactId>axiz-core-commons</artifactId>"));
    assertTrue(output.contains("<scope>compile</scope>"));
    assertTrue(output.contains("<scope>test</scope>"));
    assertFalse(output.contains("<scope>runtime</scope>"));
    assertEquals(output, CapabilityAdapters.align(output, manifest));
    assertThrows(
        IOException.class,
        () ->
            CapabilityAdapters.align(
                "<!DOCTYPE project SYSTEM 'file:///missing'><project/>", manifest));
  }

  @Test
  void libraryUpgradesNeverRequireProviderOrWorkflowChanges(@TempDir Path directory)
      throws Exception {
    var manifest =
        new ProjectManifest(
            "library",
            "com.example",
            "com.example",
            "library",
            pe.axiz.codegen.model.Structure.NONE,
            pe.axiz.codegen.model.Stack.SERVLET,
            "none",
            "2.0.0",
            null,
            null,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of());
    // Sin POM de servicio ni árbol de workflows de forma deliberada: estas operaciones no deben
    // tener efecto.
    assertFalse(CapabilityAdapters.required(directory, manifest));
    CapabilityAdapters.apply(directory, manifest);
    assertFalse(WorkflowBindings.required(directory, manifest));
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void impliedEdaAndBatchProvidersMatchTheirStoresWithoutChangingClassifiedDependencies()
      throws Exception {
    for (var style :
        java.util.List.of(
            pe.axiz.codegen.model.Structure.EDA, pe.axiz.codegen.model.Structure.BATCH)) {
      var manifest =
          new ProjectManifest(
              "worker",
              "com.example",
              "com.example.worker",
              "event-processor",
              style,
              pe.axiz.codegen.model.Stack.SERVLET,
              "aurora",
              "2.0.0",
              null,
              null,
              java.util.List.of(),
              java.util.List.of(),
              java.util.List.of());
      var expected = CapabilityAdapters.expected(manifest);
      assertTrue(
          expected.contains(
              style == pe.axiz.codegen.model.Structure.EDA
                  ? "axiz-inbox-jdbc-spring-boot-starter"
                  : "axiz-scheduling-jdbc-spring-boot-starter"),
          expected.toString());
      if (style == pe.axiz.codegen.model.Structure.EDA)
        assertTrue(expected.contains("axiz-inbox-jdbc-spring-boot-starter"), expected.toString());
      String artifact =
          style == pe.axiz.codegen.model.Structure.EDA
              ? "axiz-inbox-jdbc-spring-boot-starter"
              : "axiz-scheduling-jdbc-spring-boot-starter";
      String classified =
          "<dependency><groupId>pe.axiz</groupId><artifactId>"
              + artifact
              + "</artifactId><classifier>tests</classifier><scope>test</scope></dependency>";
      String before =
          "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><dependencies>"
              + classified
              + "</dependencies></project>";
      String aligned = CapabilityAdapters.align(before, manifest);
      assertTrue(aligned.contains("<classifier>tests</classifier>"));
      assertEquals(2, aligned.split("<artifactId>" + artifact + "</artifactId>", -1).length - 1);
      assertFalse(aligned.startsWith("<?xml"));
      assertEquals(aligned, CapabilityAdapters.align(aligned, manifest));
      assertTrue(
          CapabilityAdapters.align("<?xml version=\"1.0\"?>" + before, manifest)
              .startsWith("<?xml"));
    }
  }
}
