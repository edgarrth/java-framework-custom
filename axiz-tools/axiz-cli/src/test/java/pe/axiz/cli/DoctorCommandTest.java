package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.project.ProjectManifest;

/** What {@code axiz doctor} reports on a generated service and on each kind of drift. */
class DoctorCommandTest {

  static DoctorCommand doctor() {
    DoctorCommand doctor = new DoctorCommand();
    doctor.environment(
        new AxizCli.Environment(
            new CliHarness.FakeProcesses(Set.of("mvn", "docker")),
            null,
            Clock.systemUTC(),
            Map.of()));
    return doctor;
  }

  static List<String> messages(List<DoctorCommand.Check> checks, DoctorCommand.Level level) {
    return checks.stream()
        .filter(c -> c.level() == level)
        .map(DoctorCommand.Check::message)
        .toList();
  }

  static boolean any(List<String> messages, String fragment) {
    return messages.stream().anyMatch(m -> m.contains(fragment));
  }

  static ProjectManifest with(
      ProjectManifest m, String axizVersion, List<String> capabilities, List<String> operations) {
    return new ProjectManifest(
        m.name(),
        m.groupId(),
        m.basePackage(),
        m.archetype(),
        m.framework(),
        m.stack(),
        m.persistence(),
        axizVersion,
        m.openapi(),
        m.asyncapi(),
        capabilities,
        operations);
  }

  @Test
  void theCommandPrintsEveryCheckAndExitsWithTheErrors(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);

    CliHarness clean = new CliHarness();
    int code = clean.run("doctor", "--dir", p.toString());
    long errors = messages(doctor().checks(p), DoctorCommand.Level.ERROR).size();
    assertEquals(errors == 0 ? ExitCodes.OK : ExitCodes.PROBLEMS, code, clean.output());
    assertTrue(clean.output().contains("mvn found"), clean.output());
    assertTrue(clean.output().contains(errors + " errors, "), clean.output());

    Files.writeString(
        p.resolve("src/main/resources/application.yaml"),
        "\napi-key: k3y-12345-live\n",
        StandardOpenOption.APPEND);
    CliHarness dirty = new CliHarness();
    assertEquals(ExitCodes.PROBLEMS, dirty.run("doctor", "--dir", p.toString()));
    assertTrue(dirty.output().contains("Possible secret in src/main/resources/application.yaml"));
  }

  @Test
  void aFolderWithoutManifestIsAnInvalidProject(@TempDir Path tmp) throws Exception {
    List<DoctorCommand.Check> checks = doctor().checks(tmp);

    List<String> errors = messages(checks, DoctorCommand.Level.ERROR);
    assertTrue(any(errors, "Invalid project"), checks.toString());
    assertFalse(
        checks.stream().anyMatch(c -> c.message().startsWith("Project ")),
        "nothing else is checked");
  }

  @Test
  void versionsCapabilitiesAndContractDriftAreReported(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    ProjectManifest m = ProjectManifest.read(p);
    List<String> capabilities = new ArrayList<>(m.capabilities());
    capabilities.add("teleport");
    List<String> operations = new ArrayList<>(m.operations());
    operations.add("renamedOperation");
    with(m, "9.9.9", capabilities, operations).write(p);

    List<DoctorCommand.Check> checks = doctor().checks(p);

    List<String> errors = messages(checks, DoctorCommand.Level.ERROR);
    List<String> warnings = messages(checks, DoctorCommand.Level.WARN);
    assertTrue(any(errors, "pom parent version"), checks.toString());
    assertTrue(any(warnings, "is available: axiz upgrade --to"), checks.toString());
    assertTrue(any(warnings, "Unknown capability teleport"), checks.toString());
    assertTrue(any(warnings, "Contract and code differ"), checks.toString());

    Files.delete(p.resolve(m.openapi()));
    assertTrue(
        any(messages(doctor().checks(p), DoctorCommand.Level.ERROR), "Contract " + m.openapi()),
        "a missing contract is an error");
  }

  @Test
  void theSameOperationsInAnotherOrderAreInSync(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    ProjectManifest m = ProjectManifest.read(p);
    List<String> reversed = new ArrayList<>(m.operations());
    java.util.Collections.reverse(reversed);
    with(m, m.axizVersion(), m.capabilities(), reversed).write(p);

    assertFalse(
        any(messages(doctor().checks(p), DoctorCommand.Level.WARN), "Contract and code differ"));
  }

  @Test
  void placeholdersAreNotSecretsAndCommentsAreNotCode(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    Files.writeString(
        p.resolve("src/main/resources/application-placeholders.yaml"),
        String.join(
            "\n",
            "a-password: ${DB_PASSWORD}",
            "b-secret: # set by the platform",
            "c-token: |",
            "d-api-key: payments-api-key-ref",
            "e-private-key: local-only",
            "f-password:",
            ""));
    Path code = p.resolve("src/main/java/pe/axiz/orders/business/adapter/out/Notes.java");
    Files.writeString(
        code,
        String.join(
            "\n",
            "package pe.axiz.orders.business.adapter.out;",
            "class Notes {",
            "  // System.out.println(\"commented out\");",
            "   * Thread.sleep(10) in a Javadoc line",
            "  Object x(reactor.core.publisher.Mono<String> m) { return m.block(); }",
            "  void y() throws Exception { Thread.sleep(10); }",
            "}",
            ""));

    List<DoctorCommand.Check> checks = doctor().checks(p);

    assertFalse(
        any(messages(checks, DoctorCommand.Level.ERROR), "Possible secret"), checks.toString());
    List<String> warnings = messages(checks, DoctorCommand.Level.WARN);
    assertTrue(any(warnings, "Notes.java:6 Thread.sleep"), warnings.toString());
    assertFalse(any(warnings, "Notes.java:3"), "a comment is not code");
    assertFalse(any(warnings, "Notes.java:4"), "a Javadoc line is not code");
    assertFalse(any(warnings, "blocking call"), "block() is only reported in reactive services");
  }

  @Test
  void pendingProposalsMissingArchitectureTestsAndProposedAdrs(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    Files.writeString(p.resolve("src/main/resources/application.yaml.axiz-new"), "x: 1\n");
    try (var tests = Files.walk(p.resolve("src/test"))) {
      for (Path t : tests.filter(f -> f.endsWith("ArchitectureTest.java")).toList()) {
        Files.delete(t);
      }
    }
    Path adr = Files.createDirectories(p.resolve("docs"));
    Files.writeString(adr.resolve("0042-queues.md"), "# Queues\n\n- Status: Proposed\n");
    Files.writeString(adr.resolve("0043-cache.md"), "# Cache\n\n- Status: Accepted\n");
    Files.writeString(adr.resolve("template.md"), "- Status: Proposed\n");

    List<String> warnings = messages(doctor().checks(p), DoctorCommand.Level.WARN);

    assertTrue(any(warnings, "Pending proposal src/main/resources/application.yaml.axiz-new"));
    assertTrue(any(warnings, "No ArchitectureTest"), warnings.toString());
    assertTrue(any(warnings, "architecture contract still proposed: docs/0042-queues.md"), warnings.toString());
    assertFalse(any(warnings, "0043-cache.md"));
    assertFalse(any(warnings, "template.md"), "the template is not an architecture contract");
  }
}
