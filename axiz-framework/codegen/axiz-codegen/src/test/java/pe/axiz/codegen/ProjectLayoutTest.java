package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.project.CapabilityAdder;
import pe.axiz.codegen.project.MavenReactor;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.project.ProjectRename;

class ProjectLayoutTest {
  static Path aggregated(Path root, String module) throws Exception {
    ProjectGenerator.withDefaults()
        .generate(
            AsyncContractSyncTest.request(AsyncContractSyncTest.contract(), Structure.HEXAGONAL))
        .writeTo(root, false);
    Path application = Files.createDirectories(root.resolve(module));
    Files.move(root.resolve("src"), application.resolve("src"));
    Files.move(root.resolve("pom.xml"), application.resolve("pom.xml"));
    Files.copy(root.resolve("README.md"), application.resolve("README.md"));
    Files.writeString(
        root.resolve("pom.xml"),
        """
        <project><modelVersion>4.0.0</modelVersion>
        <parent><groupId>pe.axiz</groupId><artifactId>axiz-parent</artifactId>
        <version>2.0.0</version><relativePath/></parent>
        <groupId>pe.axiz</groupId><artifactId>orders-aggregator</artifactId>
        <version>0.1.0-SNAPSHOT</version><packaging>pom</packaging>
        <modules><module>\
        """
            + module
            + "</module></modules></project>");
    ProjectManifest m = ProjectManifest.read(root);
    ProjectManifest.builder()
        .name(m.name())
        .groupId(m.groupId())
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(m.axizVersion())
        .openapi(m.openapi() == null ? null : module + "/" + m.openapi())
        .asyncapi(m.asyncapi() == null ? null : module + "/" + m.asyncapi())
        .capabilities(m.capabilities())
        .operations(m.operations())
        .extensions(m.extensions())
        .build()
        .write(root);
    return application;
  }

  @Test
  void renameAndCapabilitiesPreserveTheAggregatorAndChartDirectory(@TempDir Path root)
      throws Exception {
    Path module = aggregated(root, "services/order-app");
    byte[] aggregator = Files.readAllBytes(root.resolve("pom.xml"));
    var before = ProjectManifest.read(root);
    ProjectRename.apply(root, before, "payment-orders", false);
    before.withName("payment-orders").write(root);
    assertTrue(ProjectRename.problems(root).isEmpty());
    String config = Files.readString(module.resolve("src/main/resources/application.yaml"));
    assertTrue(config.contains("urn:axiz:orders"));
    assertTrue(config.contains("name: payment-orders"));
    assertTrue(
        Files.readString(root.resolve(ProjectRename.LEDGER)).contains("services/order-app/src/"));
    assertTrue(
        Files.readString(root.resolve("deploy/helm/orders/Chart.yaml")).contains("payment-orders"));
    var added = new CapabilityAdder(Catalog.load()).add(root, "outbox");
    assertTrue(added.changed().contains("services/order-app/pom.xml"));
    assertTrue(
        Files.readString(module.resolve("pom.xml")).contains("axiz-outbox-spring-boot-starter"));
    assertTrue(
        added.changed().stream()
            .anyMatch(p -> p.startsWith("services/order-app/src/main/resources/db/migration/")));
    var client = new CapabilityAdder(Catalog.load()).add(root, "http-client");
    assertTrue(client.changed().stream().anyMatch(p -> p.startsWith("deploy/helm/orders/values-")));
    assertTrue(ProjectRename.problems(root).isEmpty());
    assertArrayEquals(aggregator, Files.readAllBytes(root.resolve("pom.xml")));
    assertFalse(Files.exists(root.resolve("src")));
  }

  @Test
  void bothContractsWriteToTheApplicationAndKeepSnapshotsAtTheRoot(@TempDir Path root)
      throws Exception {
    Path module = aggregated(root, "application");
    Path payload = module.resolve("src/main/java/pe/axiz/orders/domain/event/OrderCreatedV1.java");
    Files.delete(payload);
    var async = new AsyncContractSync().sync(root);
    assertTrue(
        async
            .created()
            .contains("application/src/main/java/pe/axiz/orders/domain/event/OrderCreatedV1.java"));
    assertTrue(Files.isRegularFile(root.resolve(AsyncContractSync.SNAPSHOT)));
    Path api = root.resolve(ProjectManifest.read(root).openapi());
    Files.writeString(
        api,
        Files.readString(api)
            .replace("components:\n", ContractSyncTest.NEW_OPERATION + "components:\n"));
    var http = new ContractSync(ProjectGenerator.withDefaults()).sync(root, false);
    assertTrue(http.applied(), http.toString());
    assertTrue(
        http.created().stream()
            .anyMatch(
                p ->
                    p.startsWith("application/src/main/java/")
                        && p.endsWith("RefundOrderUseCase.java")));
    assertTrue(http.proposed().stream().allMatch(p -> p.startsWith("application/")));
    assertTrue(Files.isRegularFile(root.resolve(ContractSync.SNAPSHOT)));
    assertFalse(Files.exists(root.resolve("src")));
  }

  @Test
  void contractOwnershipSelectsOneApplicationAndAmbiguityFailsBeforeEdits(@TempDir Path root)
      throws Exception {
    Path app = aggregated(root, "application");
    Path other = Files.createDirectories(root.resolve("other/src/main/resources"));
    Files.writeString(
        root.resolve("other/pom.xml"), "<project><artifactId>other</artifactId></project>");
    Files.writeString(other.resolve("application.yaml"), "spring.application.name: other\n");
    Files.writeString(
        root.resolve("pom.xml"),
        Files.readString(root.resolve("pom.xml"))
            .replace("</modules>", "<module>other</module></modules>"));
    assertEquals(app, ProjectLayout.read(root, ProjectManifest.read(root)).module());
    var m = ProjectManifest.read(root);
    var ambiguous =
        ProjectManifest.builder()
            .name(m.name())
            .groupId(m.groupId())
            .basePackage(m.basePackage())
            .archetype(m.archetype())
            .structure(m.structure())
            .stack(m.stack())
            .persistence(m.persistence())
            .axizVersion(m.axizVersion())
            .openapi("contracts/api.yaml")
            .asyncapi(null)
            .capabilities(m.capabilities())
            .operations(m.operations())
            .extensions(m.extensions())
            .build();
    byte[] before = Files.readAllBytes(app.resolve("src/main/resources/application.yaml"));
    assertThrows(
        IOException.class, () -> ProjectRename.apply(root, ambiguous, "payment-orders", false));
    assertArrayEquals(
        before, Files.readAllBytes(app.resolve("src/main/resources/application.yaml")));
    assertFalse(Files.exists(root.resolve(ProjectRename.LEDGER)));
  }

  @Test
  void declaredModulePomsAndUnsafeReactorsAreHandledWithoutFollowingLinks(@TempDir Path root)
      throws Exception {
    Path app = aggregated(root, "application");
    Files.move(app.resolve("pom.xml"), app.resolve("service.xml"));
    Files.writeString(
        root.resolve("pom.xml"),
        Files.readString(root.resolve("pom.xml"))
            .replace("<module>application</module>", "<module>application/service.xml</module>"));
    assertEquals(
        app.resolve("service.xml"), ProjectLayout.read(root, ProjectManifest.read(root)).pom());
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectLayout.read(root, ProjectManifest.read(root)).target("../outside.java"));
    Files.delete(app.resolve("src/main/resources/application.yaml"));
    Files.createSymbolicLink(
        app.resolve("src/main/resources/application.yaml"), root.resolve("pom.xml"));
    assertThrows(IOException.class, () -> ProjectLayout.read(root, ProjectManifest.read(root)));
    Files.writeString(
        root.resolve("pom.xml"),
        "<project><modules><module>../outside</module></modules></project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
    Files.writeString(
        root.resolve("pom.xml"), "<project><modules><module>.</module></modules></project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
    Files.writeString(
        root.resolve("pom.xml"),
        "<!DOCTYPE project [<!ENTITY leak SYSTEM 'file:///unreadable'>]><project>&leak;</project>");
    assertThrows(IOException.class, () -> MavenReactor.read(root));
  }
}
