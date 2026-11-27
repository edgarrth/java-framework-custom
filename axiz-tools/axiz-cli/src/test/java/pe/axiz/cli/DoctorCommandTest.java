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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.project.ProjectManifest;

/**
 * Lo que {@code axiz doctor} informa sobre un servicio generado y sobre cada tipo de desviación.
 */
class DoctorCommandTest {

  static DoctorCommand doctor() {
    DoctorCommand doctor = new DoctorCommand();
    doctor.environment(
        new AxizCli.Environment(
            new CliHarness.FakeProcesses(Set.of("mvn", "docker")),
            null,
            Clock.systemUTC(),
            Map.of(),
            () -> true));
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
    return ProjectManifest.builder()
        .name(m.name())
        .groupId(m.groupId())
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(axizVersion)
        .openapi(m.openapi())
        .asyncapi(m.asyncapi())
        .capabilities(capabilities)
        .operations(operations)
        .extensions(List.of())
        .build();
  }

  @Test
  void reactivePlansUseExplicitInstrumentationRatherThanAnUnrelatedAutodetectionFlag(
      @TempDir Path root) throws Exception {
    Path project = CliHarness.service(root, "--stack", "reactive");
    var clean = messages(doctor().checks(project), DoctorCommand.Level.ERROR);
    assertEquals(List.of(), clean, clean.toString());
    Path application;
    try (var files = Files.walk(project.resolve("src/test/java"))) {
      application =
          files
              .filter(path -> path.getFileName().toString().endsWith("ApplicationTestSupport.java"))
              .findFirst()
              .orElseThrow();
    }
    String annotated = Files.readString(application);
    // Los contratos con alcances también generan pruebas de autorización instrumentadas. Se elimina
    // cada extensión explícita para que esta verificación a nivel de proyecto no encuentre por
    // accidente una en otra clase.
    try (var sources = Files.walk(project.resolve("src/test/java"))) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        String text = Files.readString(source);
        Files.writeString(
            source,
            text.replace(
                "@ExtendWith(BlockHoundExtension.class)",
                "// @ExtendWith(BlockHoundExtension.class)"));
      }
    }
    assertTrue(
        any(
            messages(doctor().checks(project), DoctorCommand.Level.ERROR),
            "must declare @ExtendWith"));
    Path configuration = project.resolve("src/test/resources/junit-platform.properties");
    Files.createDirectories(configuration.getParent());
    Files.writeString(configuration, "junit.jupiter.extensions.autodetection.enabled=true\n");
    assertTrue(
        any(
            messages(doctor().checks(project), DoctorCommand.Level.ERROR),
            "must declare @ExtendWith"));
    Files.writeString(
        application,
        annotated.replace(
            "@ExtendWith(BlockHoundExtension.class)",
            "@org.junit.jupiter.api.extension.ExtendWith(pe.axiz.spring.test.BlockHoundExtension.class)"));
    assertEquals(List.of(), messages(doctor().checks(project), DoctorCommand.Level.ERROR));
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
        "\napi-key: " + UUID.randomUUID() + "\n",
        StandardOpenOption.APPEND);
    CliHarness dirty = new CliHarness();
    assertEquals(ExitCodes.PROBLEMS, dirty.run("doctor", "--dir", p.toString()));
    assertTrue(dirty.output().contains("Possible secret in src/main/resources/application.yaml"));
  }

  @Test
  void technologyImportsStayInPersistenceAndTheDomainRemainsIndependent(@TempDir Path tmp)
      throws Exception {
    Path p = CliHarness.service(tmp);
    Path sources = p.resolve("src/main/java/pe/axiz/orders");
    Path domain = Files.createDirectories(sources.resolve("domain"));
    Files.writeString(
        domain.resolve("Bad.java"),
        """
        package pe.axiz.orders.domain;
        import org.springframework.stereotype.Component;
        import pe.axiz.kit.jpa.JpaRepositories;
        import pe.axiz.adapter.cache.redis.RedisByteCache;
        import pe.axiz.core.lang.JsonSnapshots;
        import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
        """);
    Files.writeString(
        domain.resolve("Good.java"),
        """
        package pe.axiz.orders.domain;
        import java.math.BigDecimal;
        import org.jspecify.annotations.Nullable;
        import pe.axiz.orders.domain.Order;
        import pe.axiz.core.money.Money;
        import pe.axiz.core.lang.ValueSnapshots;
        import static pe.axiz.core.lang.ValueSnapshots.map;
        import pe.axiz.core.lang.Checks;
        import pe.axiz.core.commons.ReviewedState;
        """);
    Path persistence = Files.createDirectories(sources.resolve("adapter/out/persistence"));
    Files.writeString(
        persistence.resolve("Persistence.java"),
        "package pe.axiz.orders.adapter.out.persistence;\n"
            + "import pe.axiz.kit.jpa.JpaRepositories;\n");
    List<String> errors = messages(doctor().checks(p), DoctorCommand.Level.ERROR);
    assertTrue(any(errors, "hexagonal domain"), errors.toString());
    assertTrue(any(errors, "kits are only allowed"), errors.toString());
    assertTrue(any(errors, "technology adapters"), errors.toString());
    assertFalse(any(errors, "Good.java"), errors.toString());
    assertFalse(any(errors, "Persistence.java"), errors.toString());
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
  void credentialMetadataIsNotASecretButCredentialValuesRemainChecked(@TempDir Path tmp)
      throws Exception {
    Path project = CliHarness.service(tmp);
    Path metadata = project.resolve("src/main/resources/application-metadata.yaml");
    Files.writeString(
        metadata,
        "api-key-header: X-Api-Key\npassword-mode: LEGACY_SHA1_DIGEST\n"
            + "client:\n  api-key-header: 'X-Credential'\n  password-mode: \"TEXT\"\n");
    assertFalse(
        any(messages(doctor().checks(project), DoctorCommand.Level.ERROR), "Possible secret"));
    Files.writeString(metadata, "api-key-header: X-Api-Key\napi-key: " + UUID.randomUUID() + "\n");
    assertTrue(
        any(
            messages(doctor().checks(project), DoctorCommand.Level.ERROR),
            "application-metadata.yaml:2"));
  }

  @Test
  void literalCredentialsCannotMasqueradeAsReferencesOrEmptyBlocks(@TempDir Path tmp)
      throws Exception {
    Path project = CliHarness.service(tmp);
    Path configuration = project.resolve("src/main/resources/application-credentials.yaml");
    for (String content :
        List.of(
            "api-key: abcdefab-cdef-abcd-efab-cdefabcdefab\n",
            "client-secret: alphabetic-only\n",
            "api-key: payments-api-key-ref\n",
            "private-key: |\n  synthetic-material\n",
            "token: >\n  synthetic-material\n",
            "password: local-only\n")) {
      Files.writeString(configuration, content);
      assertTrue(
          any(
              messages(doctor().checks(project), DoctorCommand.Level.ERROR),
              "application-credentials.yaml:1"),
          "Credential literal must be reported");
    }
    Files.writeString(
        configuration, "client-secret-ref: payment-client-secret\npassword: '${DB_PASSWORD}'\n");
    assertFalse(
        any(messages(doctor().checks(project), DoctorCommand.Level.ERROR), "Possible secret"));
    Files.delete(configuration);
    Files.writeString(
        project.resolve("src/main/resources/application-credentials.properties"),
        "api-key=alphabetic-only\n");
    assertTrue(
        any(
            messages(doctor().checks(project), DoctorCommand.Level.ERROR),
            "application-credentials.properties:1"));
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
            "d-api-key-ref: payments-api-key",
            "e-private-key: ${LOCAL_PRIVATE_KEY}",
            "f-password:",
            ""));
    Path code = p.resolve("src/main/java/pe/axiz/orders/adapter/out/Notes.java");
    Files.writeString(
        code,
        String.join(
            "\n",
            "package pe.axiz.orders.adapter.out;",
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
  void pendingProposalsAndMissingArchitectureTests(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    Files.writeString(p.resolve("src/main/resources/application.yaml.axiz-new"), "x: 1\n");
    try (var tests = Files.walk(p.resolve("src/test"))) {
      for (Path t : tests.filter(f -> f.endsWith("ArchitectureTest.java")).toList()) {
        Files.delete(t);
      }
    }

    List<String> warnings = messages(doctor().checks(p), DoctorCommand.Level.WARN);

    assertTrue(any(warnings, "Pending proposal src/main/resources/application.yaml.axiz-new"));
    assertTrue(
        any(
            messages(doctor().checks(p), DoctorCommand.Level.ERROR),
            "Missing required architecture test: ArchitectureTest.java"));
  }
}
