package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.AsyncContractSync;
import pe.axiz.codegen.project.CapabilityAdder;
import pe.axiz.codegen.project.ProjectManifest;

class AsyncContractsTest {
  static Path service(Path temporary) throws Exception {
    Path directory =
        CliHarness.service(temporary, "--group", "pe.axiz", "--capability", "messaging-sqs");
    Path contract = directory.resolve("src/main/asyncapi/asyncapi.yaml");
    Files.createDirectories(contract.getParent());
    try (var in = AsyncContractsTest.class.getResourceAsStream("/events.yaml")) {
      Files.writeString(
          contract,
          new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
    }
    ProjectManifest m = ProjectManifest.read(directory);
    ProjectManifest.builder()
        .name(m.name())
        .groupId("pe.axiz")
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(m.axizVersion())
        .openapi(m.openapi())
        .asyncapi("src/main/asyncapi/asyncapi.yaml")
        .capabilities(m.capabilities())
        .operations(m.operations())
        .extensions(m.extensions())
        .build()
        .write(directory);
    CapabilityAdder.mergeYaml(
        directory.resolve("src/main/resources/application.yaml"),
        Map.of("axiz.messaging.consumer.subscriptions[0]", "pe.axiz.order.created.v1"),
        List.of());
    return directory;
  }

  @Test
  void generatesAStackCorrectHandlerFromTheDeclaredReceivedPayloadWithoutOverwritingIt(
      @TempDir Path temporary) throws Exception {
    Path directory = service(temporary);
    CliHarness cli = new CliHarness();
    assertEquals(
        0,
        cli.run("generate", "event-handler", "order-created-v1", "--dir", directory.toString()),
        cli.output());
    Path handler;
    try (var files = Files.walk(directory.resolve("src/main/java"))) {
      handler =
          files
              .filter(path -> path.getFileName().toString().equals("OrderCreatedV1Handler.java"))
              .findFirst()
              .orElseThrow();
    }
    String source = Files.readString(handler);
    assertTrue(source.contains("@HandlesEvent(\"pe.axiz.order.created.v1\")"));
    assertTrue(source.contains("TypedEventHandler<OrderCreatedV1>"));
    assertTrue(source.contains("UnsupportedOperationException"));
    Files.writeString(handler, source + "\n// Implemented by the service owner\n");
    assertEquals(
        0,
        new CliHarness()
            .run("generate", "handler", "order-created-v1", "--dir", directory.toString()));
    assertTrue(Files.readString(handler).contains("Implemented by the service owner"));
    Path contract = directory.resolve("src/main/asyncapi/asyncapi.yaml");
    Files.writeString(
        contract, Files.readString(contract).replace("action: receive", "action: send"));
    CliHarness publisherOnly = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        publisherOnly.run(
            "generate", "handler", "order-created-v1", "--dir", directory.toString()));
    assertTrue(publisherOnly.output().contains("not declared as received"));
    Files.writeString(
        contract, Files.readString(contract).replace("action: send", "action: receive"));
    var manifest = ProjectManifest.read(directory);
    manifest
        .withCapabilities(
            manifest.capabilities().stream()
                .filter(capability -> !capability.startsWith("messaging"))
                .toList())
        .write(directory);
    CliHarness missingCapability = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        missingCapability.run(
            "generate", "handler", "order-created-v1", "--dir", directory.toString()));
    assertTrue(missingCapability.output().contains("requires a messaging capability"));
  }

  @Test
  void doctorLocatesSubscriptionsInTheMavenModuleOwningTheContract(@TempDir Path temporary)
      throws Exception {
    Path directory = service(temporary);
    assertEquals(
        0,
        new CliHarness()
            .run("contract", "sync", "--type", "asyncapi", "--dir", directory.toString()));
    Path module = Files.createDirectories(directory.resolve("application"));
    Files.move(directory.resolve("src"), module.resolve("src"));
    Path rootPom = directory.resolve("pom.xml");
    String servicePom = Files.readString(rootPom);
    Files.writeString(module.resolve("pom.xml"), servicePom);
    Files.writeString(
        rootPom,
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>pe.axiz</groupId>
            <artifactId>axiz-parent</artifactId>
            <version>2.0.0</version>
            <relativePath/>
          </parent>
          <groupId>pe.axiz</groupId>
          <artifactId>orders-aggregator</artifactId>
          <version>0.1.0-SNAPSHOT</version>
          <packaging>pom</packaging>
          <modules><module>application</module></modules>
        </project>
        """);
    ProjectManifest m = ProjectManifest.read(directory);
    ProjectManifest.builder()
        .name(m.name())
        .groupId(m.groupId())
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(m.axizVersion())
        .openapi("application/" + m.openapi())
        .asyncapi("application/" + m.asyncapi())
        .capabilities(m.capabilities())
        .operations(m.operations())
        .extensions(m.extensions())
        .build()
        .write(directory);
    var errors =
        DoctorCommandTest.messages(
            DoctorCommandTest.doctor().checks(directory), DoctorCommand.Level.ERROR);
    assertTrue(errors.isEmpty(), errors.toString());
    Files.writeString(
        module.resolve("pom.xml"),
        servicePom.replace(
            "<artifactId>axiz-messaging-spring-boot-starter</artifactId>",
            "<artifactId>axiz-missing-starter</artifactId>"));
    assertTrue(
        DoctorCommandTest.any(
            DoctorCommandTest.messages(
                DoctorCommandTest.doctor().checks(directory), DoctorCommand.Level.ERROR),
            "not in the Maven reactor poms"));
  }

  @Test
  void syncGenerateLintAndDoctorUseTheSameAsyncApiSource(@TempDir Path temporary) throws Exception {
    Path directory = service(temporary);
    CliHarness h = new CliHarness();
    assertEquals(
        0,
        h.run("contract", "sync", "--type", "asyncapi", "--dir", directory.toString()),
        h.output());
    assertTrue(Files.exists(directory.resolve(AsyncContractSync.SNAPSHOT)));
    Path record;
    try (var files = Files.walk(directory.resolve("src/main/java"))) {
      record =
          files
              .filter(path -> path.getFileName().toString().equals("OrderCreatedV1.java"))
              .findFirst()
              .orElseThrow();
    }
    Files.delete(record);
    assertEquals(
        0,
        new CliHarness()
            .run("generate", "event", "pe.axiz.order.created.v1", "--dir", directory.toString()));
    assertTrue(Files.readString(record).contains("String id"));
    assertEquals(
        0,
        new CliHarness()
            .run("contract", "lint", "--type", "asyncapi", "--dir", directory.toString()));
    assertEquals(
        0,
        new CliHarness()
            .run(
                "contract",
                "lint",
                directory.resolve("src/main/asyncapi/asyncapi.yaml").toString()));
    assertTrue(
        DoctorCommandTest.messages(
                DoctorCommandTest.doctor().checks(directory), DoctorCommand.Level.ERROR)
            .isEmpty());
    CliHarness unknown = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        unknown.run("generate", "event", "missing", "--dir", directory.toString()));
    assertTrue(unknown.output().contains("exactly one AsyncAPI"));
  }

  @Test
  void incompatiblePublishedTypeCannotBeOverriddenByAllowBreaking(@TempDir Path temporary)
      throws Exception {
    Path directory = service(temporary);
    assertEquals(
        0,
        new CliHarness()
            .run("contract", "sync", "--type", "asyncapi", "--dir", directory.toString()));
    Path source = directory.resolve("src/main/asyncapi/asyncapi.yaml");
    String snapshot = Files.readString(directory.resolve(AsyncContractSync.SNAPSHOT));
    Files.writeString(source, snapshot.replace("id: {type: string}", "id: {type: integer}"));
    CliHarness diff = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        diff.run(
            "contract",
            "diff",
            "--type",
            "asyncapi",
            directory.resolve(AsyncContractSync.SNAPSHOT).toString(),
            source.toString()));
    assertTrue(diff.output().contains("BREAKING"));
    assertEquals(
        ExitCodes.PROBLEMS,
        new CliHarness()
            .run("contract", "sync", "--type", "asyncapi", "--dir", directory.toString()));
    CliHarness forced = new CliHarness();
    assertEquals(
        ExitCodes.PROBLEMS,
        forced.run(
            "contract",
            "sync",
            "--type",
            "asyncapi",
            "--allow-breaking",
            "--dir",
            directory.toString()));
    assertTrue(forced.output().contains("cannot replace a published type"));
    assertEquals(snapshot, Files.readString(directory.resolve(AsyncContractSync.SNAPSHOT)));
  }

  @Test
  void doctorRejectsMissingStaleAndMismatchedSubscriptions(@TempDir Path temporary)
      throws Exception {
    Path directory = service(temporary);
    assertTrue(
        DoctorCommandTest.any(
            DoctorCommandTest.messages(
                DoctorCommandTest.doctor().checks(directory), DoctorCommand.Level.ERROR),
            "snapshot differs"));
    assertEquals(
        0,
        new CliHarness()
            .run("contract", "sync", "--type", "asyncapi", "--dir", directory.toString()));
    CapabilityAdder.mergeYaml(
        directory.resolve("src/main/resources/application.yaml"),
        Map.of("axiz.messaging.consumer.subscriptions[0]", "wrong.v1"),
        List.of());
    assertTrue(
        DoctorCommandTest.any(
            DoctorCommandTest.messages(
                DoctorCommandTest.doctor().checks(directory), DoctorCommand.Level.ERROR),
            "must equal AsyncAPI"));
    Path profile = directory.resolve("src/main/resources/application-prod.yaml");
    Files.writeString(profile, "axiz.messaging.consumer.subscriptions: [wrong.v2]\n");
    assertTrue(
        DoctorCommandTest.any(
            DoctorCommandTest.messages(
                DoctorCommandTest.doctor().checks(directory), DoctorCommand.Level.ERROR),
            "application-prod.yaml"));
    Files.delete(directory.resolve("src/main/asyncapi/asyncapi.yaml"));
    assertTrue(
        DoctorCommandTest.any(
            DoctorCommandTest.messages(
                DoctorCommandTest.doctor().checks(directory), DoctorCommand.Level.ERROR),
            "Invalid AsyncAPI"));
  }

  @Test
  void asyncOnlyProjectsDoNotRequireTheTypeSwitch(@TempDir Path temporary) throws Exception {
    Path directory = service(temporary);
    ProjectManifest m = ProjectManifest.read(directory);
    ProjectManifest.builder()
        .name(m.name())
        .groupId(m.groupId())
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(m.axizVersion())
        .openapi(null)
        .asyncapi(m.asyncapi())
        .capabilities(m.capabilities())
        .operations(m.operations())
        .extensions(m.extensions())
        .build()
        .write(directory);
    assertEquals(0, new CliHarness().run("contract", "sync", "--dir", directory.toString()));
    assertEquals(0, new CliHarness().run("contract", "lint", "--dir", directory.toString()));
  }
}
