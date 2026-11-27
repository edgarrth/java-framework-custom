package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.project.MavenReactor;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectManifest;

class ModuleCommandsTest {
  static Path aggregate(Path project) throws Exception {
    Path application = Files.createDirectory(project.resolve("application"));
    Files.move(project.resolve("src"), application.resolve("src"));
    Files.move(project.resolve("pom.xml"), application.resolve("pom.xml"));
    Files.copy(project.resolve("README.md"), application.resolve("README.md"));
    Path library =
        Files.createDirectories(project.resolve("domain-model/src/main/java/pe/axiz/model"));
    Files.writeString(
        library.resolve("Marker.java"),
        "package pe.axiz.model; public record Marker(String id) {}\n");
    Files.writeString(
        project.resolve("domain-model/pom.xml"),
        """
        <project><modelVersion>4.0.0</modelVersion><parent>
        <groupId>pe.axiz</groupId><artifactId>axiz-parent-core</artifactId>
        <version>2.0.0</version><relativePath/></parent><artifactId>domain-model</artifactId>
        <version>0.1.0-SNAPSHOT</version></project>
        """);
    Files.writeString(project.resolve("domain-model/README.md"), "Domain model\n");
    Files.writeString(
        project.resolve("pom.xml"),
        """
        <project><modelVersion>4.0.0</modelVersion><parent>
        <groupId>pe.axiz</groupId><artifactId>axiz-parent</artifactId>
        <version>2.0.0</version><relativePath/></parent><groupId>pe.axiz</groupId>
        <artifactId>orders-aggregator</artifactId><version>0.1.0-SNAPSHOT</version><packaging>pom</packaging>
        <modules><module>domain-model</module><module>application</module></modules></project>
        """);
    ProjectManifest m = ProjectManifest.read(project);
    ProjectManifest.builder()
        .name(m.name())
        .groupId(m.groupId())
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(m.axizVersion())
        .openapi(m.openapi() == null ? null : "application/" + m.openapi())
        .asyncapi(m.asyncapi() == null ? null : "application/" + m.asyncapi())
        .capabilities(m.capabilities())
        .operations(m.operations())
        .extensions(m.extensions())
        .build()
        .write(project);
    return application;
  }

  @Test
  void reactiveArtifactsAndDoctorUseTheApplicationWithoutInstrumentingItsPureLibrary(
      @TempDir Path root) throws Exception {
    Path events = root.resolve("events.yaml");
    try (var input = ModuleCommandsTest.class.getResourceAsStream("/events.yaml")) {
      Files.write(events, java.util.Objects.requireNonNull(input).readAllBytes());
    }
    Path project =
        CliHarness.service(
            root,
            "--group",
            "pe.axiz",
            "--package",
            "pe.axiz.orders",
            "--stack",
            "reactive",
            "--capability",
            "messaging-sqs",
            "--asyncapi",
            events.toString());
    Path app = aggregate(project);
    byte[] aggregator = Files.readAllBytes(project.resolve("pom.xml"));
    byte[] library = Files.readAllBytes(project.resolve("domain-model/pom.xml"));
    Path handler =
        app.resolve("src/main/java/pe/axiz/orders/adapter/in/messaging/OrderCreatedV1Handler.java");
    Files.delete(handler);
    CliHarness cli = new CliHarness();
    assertEquals(
        0,
        cli.run("generate", "event-handler", "order-created-v1", "--dir", project.toString()),
        cli.output());
    assertTrue(Files.isRegularFile(handler));
    assertEquals(
        0,
        cli.run("generate", "migration", "extra-trace", "--dir", project.toString()),
        cli.output());
    assertTrue(
        Files.isRegularFile(app.resolve("src/main/resources/db/migration/V2__extra_trace.sql")));
    assertEquals(0, cli.run("add", "outbox", "--dir", project.toString()), cli.output());
    assertTrue(
        Files.readString(app.resolve("pom.xml")).contains("axiz-outbox-spring-boot-starter"));
    assertEquals(0, cli.run("doctor", "--dir", project.toString()), cli.output());
    assertArrayEquals(aggregator, Files.readAllBytes(project.resolve("pom.xml")));
    assertArrayEquals(library, Files.readAllBytes(project.resolve("domain-model/pom.xml")));
    assertFalse(Files.exists(project.resolve("src")));
  }

  @Test
  void customPomCannotSilentlyProduceAnIncompatibleStandardBuild(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    Path app = aggregate(project);
    ProjectManifest.read(project).write(project);
    Files.move(app.resolve("pom.xml"), app.resolve("service.xml"));
    Path reactor = project.resolve("pom.xml");
    Files.writeString(
        reactor,
        Files.readString(reactor)
            .replace("<module>application</module>", "<module>application/service.xml</module>"));
    byte[] beforePom = Files.readAllBytes(reactor);
    byte[] beforeService = Files.readAllBytes(app.resolve("service.xml"));
    byte[] beforeManifest = Files.readAllBytes(project.resolve(ProjectManifest.PATH));
    byte[] beforeCi = Files.readAllBytes(project.resolve(".github/workflows/ci.yaml"));
    CliHarness cli = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        cli.run("upgrade", "--to", "2.0.0", "--skip-rewrite", "--dir", project.toString()),
        cli.output());
    assertArrayEquals(beforePom, Files.readAllBytes(reactor));
    assertArrayEquals(beforeService, Files.readAllBytes(app.resolve("service.xml")));
    assertArrayEquals(beforeManifest, Files.readAllBytes(project.resolve(ProjectManifest.PATH)));
    assertArrayEquals(beforeCi, Files.readAllBytes(project.resolve(".github/workflows/ci.yaml")));
    assertTrue(Files.isRegularFile(project.resolve("target/rewrite/rewrite.patch")));
  }

  @Test
  void upgradeRenamesOnlyTheServicePomAndBindsItsIndependentPipelines(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    Path app = aggregate(project);
    ProjectManifest before = ProjectManifest.read(project);
    before.write(project);
    for (MavenReactor.Module module : MavenReactor.read(project)) {
      if (module.axizParent())
        Files.writeString(
            module.pom(), PomEditor.setParentVersion(Files.readString(module.pom()), "2.0.1"));
    }
    CliHarness cli = new CliHarness();
    assertEquals(
        0,
        cli.run(
            "upgrade",
            "--to",
            "2.0.0",
            "--rename",
            "payment-orders",
            "--skip-rewrite",
            "--dir",
            project.toString()),
        cli.output());
    assertTrue(
        Files.readString(project.resolve("pom.xml"))
            .contains("<artifactId>orders-aggregator</artifactId>"));
    assertTrue(
        Files.readString(app.resolve("pom.xml"))
            .contains("<artifactId>payment-orders</artifactId>"));
    assertTrue(
        Files.readString(project.resolve("domain-model/pom.xml"))
            .contains("<artifactId>domain-model</artifactId>"));
    Map<?, ?> ci =
        SafeYaml.create(1_000_000)
            .load(Files.readString(project.resolve(".github/workflows/ci.yaml")));
    Map<?, ?> jobs = (Map<?, ?>) ci.get("jobs");
    Map<?, ?> build = (Map<?, ?>) jobs.get("build");
    assertEquals("application", ((Map<?, ?>) build.get("with")).get("module"));
    assertEquals("deploy/helm/orders", ((Map<?, ?>) build.get("with")).get("chart"));
    assertTrue(
        Files.readString(project.resolve("release-please-config.json"))
            .contains("application/pom.xml"));
    assertEquals(0, cli.run("doctor", "--dir", project.toString()), cli.output());
  }
}
