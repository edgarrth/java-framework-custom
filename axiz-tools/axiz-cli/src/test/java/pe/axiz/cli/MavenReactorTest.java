package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectManifest;

class MavenReactorTest {
  private static Path platform(Path root) throws IOException {
    CliHarness cli = new CliHarness();
    assertEquals(
        0,
        cli.run(
            "new",
            "axiz-geo",
            "--archetype",
            "starter",
            "--group",
            "pe.axiz",
            "--package",
            "pe.axiz.core.geo",
            "--output",
            root.toString()),
        cli.output());
    return root.resolve("axiz-geo");
  }

  @Test
  void doctorChecksGeneratedStarterModulesWithoutRequiringAParentOrSourcesOnItsAggregator(
      @TempDir Path root) throws Exception {
    Path project = platform(root);
    Path rootPom = project.resolve("pom.xml");
    Files.writeString(
        rootPom, Files.readString(rootPom).replaceFirst("(?s)\\s*<parent>.*?</parent>", ""));
    List<String> clean =
        DoctorCommandTest.messages(
            DoctorCommandTest.doctor().checks(project), DoctorCommand.Level.ERROR);
    assertEquals(List.of(), clean, clean.toString());
    List<MavenReactor.Module> modules = MavenReactor.read(project);
    assertEquals(4, modules.size());
    Path spring =
        modules.stream()
            .filter(MavenReactor.Module::axizParent)
            .map(MavenReactor.Module::pom)
            .filter(path -> path.toString().contains("autoconfigure"))
            .findFirst()
            .orElseThrow();
    Files.writeString(spring, PomEditor.setParentVersion(Files.readString(spring), "1.0.0"));
    List<String> errors =
        DoctorCommandTest.messages(
            DoctorCommandTest.doctor().checks(project), DoctorCommand.Level.ERROR);
    assertTrue(
        errors.stream()
            .anyMatch(message -> message.contains("1.0.0") && message.contains("autoconfigure")),
        errors.toString());
  }

  @Test
  void upgradesAllFrameworkParentsAfterRecipesAndKeepsTheApplicationVersion(@TempDir Path root)
      throws Exception {
    Path project = platform(root);
    String manifestBefore = Files.readString(project.resolve(ProjectManifest.PATH));
    List<String> before =
        MavenReactor.read(project).stream()
            .map(
                module -> {
                  try {
                    return Files.readString(module.pom());
                  } catch (IOException error) {
                    throw new AssertionError(error);
                  }
                })
            .toList();
    CliHarness cli = new CliHarness();
    cli.processes.beforeRun =
        ignored -> {
          try {
            assertEquals(manifestBefore, Files.readString(project.resolve(ProjectManifest.PATH)));
            assertEquals(
                before,
                MavenReactor.read(project).stream()
                    .map(
                        module -> {
                          try {
                            return Files.readString(module.pom());
                          } catch (IOException error) {
                            throw new AssertionError(error);
                          }
                        })
                    .toList());
          } catch (IOException error) {
            throw new AssertionError(error);
          }
        };
    assertEquals(0, cli.run("upgrade", "--to", "2.1.0", "--dir", project.toString()), cli.output());
    assertEquals("2.1.0", ProjectManifest.read(project).axizVersion());
    for (MavenReactor.Module module : MavenReactor.read(project)) {
      if (module.axizParent()) assertEquals("2.1.0", module.parentVersion());
      assertTrue(Files.readString(module.pom()).contains("<version>0.1.0-SNAPSHOT</version>"));
    }
    assertFalse(cli.processes.commands.isEmpty());
  }

  @Test
  void rejectsOutsideMissingCyclicAndSymlinkedModulesBeforeReadingThem(@TempDir Path root)
      throws Exception {
    Files.writeString(
        root.resolve("pom.xml"),
        "<project><modules><module>../outside</module></modules></project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
    Files.writeString(
        root.resolve("pom.xml"), "<project><modules><module>.</module></modules></project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
    Files.writeString(
        root.resolve("pom.xml"), "<project><modules><module>missing</module></modules></project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
    Path child = Files.createDirectory(root.resolve("child"));
    Files.createSymbolicLink(child.resolve("pom.xml"), root.resolve("pom.xml"));
    Files.writeString(
        root.resolve("pom.xml"), "<project><modules><module>child</module></modules></project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
  }

  @Test
  void rejectsExternalEntitiesAndDoesNotParseFakeModulesInsideDependencyManagement(
      @TempDir Path root) throws Exception {
    Path secret = root.resolve("secret.txt");
    Files.writeString(secret, "must-not-read");
    Files.writeString(
        root.resolve("pom.xml"),
        "<!DOCTYPE project [<!ENTITY leak SYSTEM \""
            + secret.toUri()
            + "\">]><project><modules><module>&leak;</module></modules></project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
    Files.writeString(
        root.resolve("pom.xml"),
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><dependencyManagement><modules><module>not-a-module</module></modules></dependencyManagement></project>");
    assertEquals(1, MavenReactor.read(root).size());
  }
}
