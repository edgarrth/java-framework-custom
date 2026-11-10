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
  @CsvSource({"servlet, axiz-adapter-outbox-jdbc", "reactive, axiz-adapter-database-auth-aws"})
  void currentVersionUpgradeAddsTheMissingRuntimeAdapterAndTheNextUpgradeIsANoop(
      String stack, String adapter, @TempDir Path directory) throws Exception {
    Path project =
        CliHarness.service(
            directory, "--stack", stack, "--persistence", "aurora", "--capability", "outbox");
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
    assertTrue(
        aligned.contains("<artifactId>" + adapter + "</artifactId>\n      <scope>runtime</scope>"),
        aligned);
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
        CliHarness.service(directory, "--persistence", "aurora", "--capability", "outbox");
    var manifest = ProjectManifest.read(project);
    String input =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <dependencies>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-core-commons</artifactId></dependency>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-outbox-jdbc</artifactId>
              <scope>compile</scope></dependency>
          </dependencies>
          <profiles><profile><id>custom</id><dependencies>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-outbox-jdbc</artifactId>
              <scope>test</scope></dependency>
          </dependencies></profile></profiles>
        </project>
        """;
    String output = CapabilityAdapters.align(input, manifest);
    assertTrue(output.contains("<artifactId>axiz-core-commons</artifactId>"));
    assertTrue(output.contains("<scope>runtime</scope>"));
    assertTrue(output.contains("<scope>test</scope>"));
    assertFalse(output.contains("<scope>compile</scope>"));
    assertEquals(output, CapabilityAdapters.align(output, manifest));
    assertThrows(
        IOException.class,
        () ->
            CapabilityAdapters.align(
                "<!DOCTYPE project SYSTEM 'file:///missing'><project/>", manifest));
  }
}
