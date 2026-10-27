package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.render.JavaFormatter;

/** A project leaves the CLI formatted as its spotless:check expects. */
class FormattingTest {

  @Test
  void theJarFormatsTheSourcesItGenerates(@TempDir Path tmp) throws Exception {
    // the tests run with the exports the jar declares in its manifest (Add-Exports)
    assertTrue(JavaFormatter.available(), "google-java-format runs with the jar's exports");
    CliHarness h = new CliHarness();
    Path contract = CliHarness.contract(tmp);
    assertEquals(
        0,
        h.run(
            "new",
            "orders-business",
            "--archetype",
            "business",
            "--openapi",
            contract.toString(),
            "--output",
            tmp.toString()),
        h.output());
    assertTrue(h.processes.commands.isEmpty(), "nothing else runs to format");
    try (Stream<Path> files = Files.walk(tmp.resolve("orders-business/src"))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(source, JavaFormatter.format(source), file + " is formatted");
      }
    }
  }

  @Test
  void withoutTheFormatterTheWrapperRunsSpotless(@TempDir Path tmp) throws Exception {
    CliHarness h = withoutFormatter();
    Path project = generate(h, tmp);
    assertEquals(
        List.of(project.resolve("mvnw").toString(), "-q", "-B", "spotless:apply"),
        h.processes.commands.get(0));
    assertTrue(h.output().contains("Formatting the sources"), h.output());
  }

  @Test
  void aFailingSpotlessLeavesTheProjectAndSaysWhatToRun(@TempDir Path tmp) throws Exception {
    CliHarness failing = withoutFormatter();
    failing.processes.exitCode = 1;
    generate(failing, tmp.resolve("a"));
    assertTrue(failing.output().contains("spotless:apply exited with 1"), failing.output());

    CliHarness broken = withoutFormatter();
    broken.processes.failure = new IOException("no such file");
    generate(broken, tmp.resolve("b"));
    assertTrue(
        broken.output().contains("Could not run spotless:apply (no such file)"), broken.output());
  }

  @Test
  void otherCommandsFormatWhatTheyAddAndNeedMavenOtherwise(@TempDir Path tmp) throws Exception {
    Path project = CliHarness.service(tmp);
    CliHarness add = withoutFormatter();
    assertEquals(0, add.run("add", "lock", "--dir", project.toString()), add.output());
    assertEquals("spotless:apply", add.processes.commands.get(0).get(3));

    Files.delete(project.resolve("mvnw"));
    CliHarness noMaven = withoutFormatter();
    noMaven.processes.installed.clear();
    assertEquals(
        0,
        noMaven.run("generate", "usecase", "approve-order", "--dir", project.toString()),
        noMaven.output());
    assertTrue(noMaven.processes.commands.isEmpty());
    assertTrue(
        noMaven.output().contains("Sources not formatted (no Maven found)"), noMaven.output());

    CliHarness again = withoutFormatter();
    assertEquals(0, again.run("add", "lock", "--dir", project.toString()), again.output());
    assertTrue(again.processes.commands.isEmpty(), "nothing added, nothing to format");
  }

  private static CliHarness withoutFormatter() {
    CliHarness h = new CliHarness();
    h.javaFormatter = false;
    return h;
  }

  private static Path generate(CliHarness h, Path dir) throws IOException {
    Files.createDirectories(dir);
    Path contract = CliHarness.contract(dir);
    assertEquals(
        0,
        h.run(
            "new",
            "orders-business",
            "--archetype",
            "business",
            "--openapi",
            contract.toString(),
            "--output",
            dir.toString()),
        h.output());
    return dir.resolve("orders-business");
  }
}
