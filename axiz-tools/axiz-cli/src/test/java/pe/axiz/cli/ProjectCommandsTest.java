package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.project.ProjectManifest;

class ProjectCommandsTest {

  @Test
  void addsCapabilities(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    CliHarness h = new CliHarness();
    assertEquals(0, h.run("add", "cache-redis", "lock", "--dir", p.toString()), h.output());
    String pom = Files.readString(p.resolve("pom.xml"));
    assertTrue(
        pom.contains("axiz-cache-spring-boot-starter")
            && pom.contains("axiz-core-cache-redis")
            && pom.contains("axiz-lock-spring-boot-starter"));
    assertTrue(h.output().contains("spring.data.redis.url"), "ConfigMap keys are listed");
    assertTrue(h.output().contains("Example:"));
    assertEquals(
        ExitCodes.PROBLEMS, new CliHarness().run("add", "teleport", "--dir", p.toString()));
  }

  @Test
  void generatesArtifactsAndErrorCodes(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    String base = "src/main/java/pe/axiz/orders/business/";
    assertEquals(
        0, new CliHarness().run("generate", "usecase", "approve-order", "--dir", p.toString()));
    assertTrue(Files.exists(p.resolve(base + "application/usecase/ApproveOrderUseCase.java")));
    CliHarness again = new CliHarness();
    assertEquals(0, again.run("generate", "usecase", "approve-order", "--dir", p.toString()));
    assertTrue(again.output().contains("not overwritten"));

    assertEquals(
        0,
        new CliHarness()
            .run(
                "generate",
                "error-code",
                "limit-exceeded",
                "--title",
                "Daily limit exceeded",
                "--dir",
                p.toString()));
    assertEquals(
        0,
        new CliHarness()
            .run(
                "generate",
                "error-code",
                "account-blocked",
                "--category",
                "forbidden",
                "--dir",
                p.toString()));
    String errors = Files.readString(p.resolve(base + "domain/OrdersBusinessErrors.java"));
    assertTrue(
        errors.contains("ORDERS-ORDERSBUSINESS-0001")
            && errors.contains("ORDERS-ORDERSBUSINESS-0002"),
        errors);
    assertTrue(errors.contains("ErrorCategory.FORBIDDEN"));
    String messages = Files.readString(p.resolve("src/main/resources/messages.properties"));
    assertTrue(messages.contains("error.ORDERS-ORDERSBUSINESS-0002=account blocked"), messages);

    assertEquals(
        0,
        new CliHarness()
            .run(
                "generate",
                "mapper",
                "order",
                "--source",
                "Order",
                "--target",
                "Order",
                "--dir",
                p.toString()));
    assertTrue(
        Files.readString(p.resolve("pom.xml")).contains("<artifactId>mapstruct</artifactId>"));

    CliHarness wrong = new CliHarness();
    assertEquals(ExitCodes.PROBLEMS, wrong.run("generate", "command", "x", "--dir", p.toString()));
    assertTrue(wrong.output().contains("does not apply to the hexagonal style"));
  }

  @Test
  void generatesHttpClients(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    CliHarness h = new CliHarness();
    assertEquals(
        0,
        h.run(
            "generate",
            "http-client",
            "order-service",
            "--contract",
            tmp.resolve("orders.yaml").toString(),
            "--dir",
            p.toString()),
        h.output());
    assertTrue(
        Files.exists(
            p.resolve(
                "src/main/java/pe/axiz/orders/business/adapter/out/orderservice/OrderServiceClient.java")));
    assertTrue(
        Files.readString(p.resolve("src/main/resources/application.yaml"))
            .contains("order-service:"));
    assertTrue(h.output().contains("axiz add http-client"));
  }

  @Test
  void contractCommands(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    assertEquals(0, new CliHarness().run("contract", "lint", "--dir", p.toString()));
    CliHarness sync = new CliHarness();
    assertEquals(0, sync.run("contract", "sync", "--dir", p.toString()));
    assertTrue(sync.output().contains("in sync"), sync.output());

    Path changed = tmp.resolve("changed.yaml");
    Files.writeString(
        changed,
        Files.readString(tmp.resolve("orders.yaml"))
            .replace(
                "    delete:\n      operationId: cancelOrder",
                "    x-gone:\n      operationId: cancelOrder"));
    CliHarness diff = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        diff.run("contract", "diff", tmp.resolve("orders.yaml").toString(), changed.toString()));
    assertTrue(diff.output().contains("BREAKING"));

    Files.copy(
        changed,
        p.resolve("src/main/openapi/openapi.yaml"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertEquals(
        ExitCodes.PROBLEMS, new CliHarness().run("contract", "sync", "--dir", p.toString()));
    assertEquals(
        0, new CliHarness().run("contract", "sync", "--allow-breaking", "--dir", p.toString()));
    assertFalse(ProjectManifest.read(p).operations().contains("cancelOrder"));
  }

  @Test
  void upgradeUpdatesVersionsAndRunsTheRecipes(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    CliHarness h = new CliHarness();
    assertEquals(0, h.run("upgrade", "--to", "1.2.0", "--dir", p.toString()), h.output());
    assertTrue(Files.readString(p.resolve("pom.xml")).contains("<version>1.2.0</version>"));
    assertEquals("1.2.0", ProjectManifest.read(p).axizVersion());
    List<String> command = h.processes.commands.get(0);
    // generated projects ship the Maven wrapper, so the recipes run through it
    assertEquals("./mvnw", command.get(0));
    assertTrue(
        command.contains("-Drewrite.recipeArtifactCoordinates=pe.axiz:axiz-rewrite-recipes:1.2.0"));
    assertEquals(
        ExitCodes.PROBLEMS,
        new CliHarness().run("upgrade", "--to", "latest!", "--dir", p.toString()));
  }

  @Test
  void versionsAreReleaseNumbersWithAnOptionalSuffix() {
    for (String valid : List.of("1.2.0", "10.20.30", "1.2.0-SNAPSHOT", "1.2.0-rc.1")) {
      assertTrue(UpgradeCommand.isVersion(valid), valid);
    }
    for (String invalid :
        List.of("", "1.2", "1.2.0.", "v1.2.0", "1.2.0-", "1.2.0-rc_1", "1.2.0-rc-1", "latest!")) {
      assertFalse(UpgradeCommand.isVersion(invalid), invalid);
    }
  }

  @Test
  void localEnvironment(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    CliHarness h = new CliHarness();
    assertEquals(0, h.run("local", "up", "--dir", p.toString()));
    assertEquals(List.of("docker", "compose", "up", "-d", "--wait"), h.processes.commands.get(0));
    assertEquals(0, h.run("local", "down", "-v", "--dir", p.toString()));
    assertEquals(List.of("docker", "compose", "down", "--volumes"), h.processes.commands.get(1));
  }

  @Test
  void doctorFindsProblems(@TempDir Path tmp) throws Exception {
    Path p = CliHarness.service(tmp);
    DoctorCommand doctor = new DoctorCommand();
    doctor.environment(
        new AxizCli.Environment(
            new CliHarness.FakeProcesses(java.util.Set.of("mvn")),
            null,
            java.time.Clock.systemUTC(),
            java.util.Map.of(),
            () -> true));
    List<DoctorCommand.Check> clean = doctor.checks(p);
    assertTrue(
        clean.stream()
            .filter(c -> c.level() == DoctorCommand.Level.ERROR)
            .allMatch(c -> c.message().startsWith("Java ")),
        clean.toString());
    assertTrue(clean.stream().anyMatch(c -> c.message().equals("docker not found on the PATH")));

    Files.writeString(
        p.resolve("src/main/resources/application-local.yaml"),
        "db:\n  password: Sup3rS3cret!\n",
        java.nio.file.StandardOpenOption.APPEND);
    Path java = p.resolve("src/main/java/pe/axiz/orders/business/adapter/out/Bad.java");
    Files.writeString(
        java,
        "package pe.axiz.orders.business.adapter.out;\n"
            + "class Bad { void x() { System.out.println(1); } }\n");
    Files.writeString(
        p.resolve("pom.xml"),
        Files.readString(p.resolve("pom.xml"))
            .replace("axiz-audit-spring-boot-starter", "something-else"));
    List<DoctorCommand.Check> dirty = doctor.checks(p);
    assertTrue(
        dirty.stream()
            .anyMatch(
                c ->
                    c.level() == DoctorCommand.Level.ERROR
                        && c.message().contains("Possible secret")),
        dirty.toString());
    assertTrue(dirty.stream().anyMatch(c -> c.message().contains("Bad.java:2 use SLF4J")));
    assertTrue(dirty.stream().anyMatch(c -> c.message().contains("axiz add audit")));
  }

  @Test
  void catalogListsArchetypesAndCapabilities() {
    CliHarness h = new CliHarness();
    assertEquals(0, h.run("catalog"));
    assertTrue(h.output().contains("event-processor") && h.output().contains("idempotency-redis"));
    CliHarness version = new CliHarness();
    assertEquals(0, version.run("--version"));
    assertTrue(version.output().contains("framework"));
  }
}
