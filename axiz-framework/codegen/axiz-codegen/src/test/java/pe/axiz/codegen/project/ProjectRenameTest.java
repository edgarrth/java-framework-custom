package pe.axiz.codegen.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

class ProjectRenameTest {
  @Test
  void rejectsSymlinkedSourcesChartsLedgersAndProposalsBeforeWriting(@TempDir Path temporary)
      throws Exception {
    Path outside = Files.writeString(temporary.resolve("outside.yaml"), "name: untouched\n");
    for (String relative :
        List.of(
            "src/main/resources/application.yaml",
            ".axiz/identities.yaml",
            "deploy/helm/orders/Chart.yaml",
            "deploy/helm/orders/templates/migration-service-aliases.yaml")) {
      Path dir =
          Files.createDirectories(temporary.resolve("case-" + Math.abs(relative.hashCode())));
      Path resources = Files.createDirectories(dir.resolve("src/main/resources"));
      Files.writeString(
          resources.resolve("application.yaml"), "spring.application.name: original-orders\n");
      Path chart = Files.createDirectories(dir.resolve("deploy/helm/orders/templates")).getParent();
      Files.writeString(chart.resolve("values.yaml"), "service.port: 80\n");
      Path linked = dir.resolve(relative);
      Files.createDirectories(linked.getParent());
      Files.deleteIfExists(linked);
      Files.createSymbolicLink(linked, outside);
      assertThrows(
          IOException.class,
          () -> ProjectRename.apply(dir, manifest("original-orders"), "payment-orders", false),
          relative);
      assertEquals("name: untouched\n", Files.readString(outside));
      if (!relative.equals("src/main/resources/application.yaml"))
        assertEquals(
            "spring.application.name: original-orders\n",
            Files.readString(resources.resolve("application.yaml")));
    }
  }

  @Test
  void preservesIdentityAndStatefulResourcesAcrossSuccessiveRenames(@TempDir Path dir)
      throws Exception {
    Path resources = Files.createDirectories(dir.resolve("src/main/resources"));
    Files.writeString(
        resources.resolve("application.yaml"),
        """
        spring.application.name: old-business
        axiz.messaging.source: urn:custom:payments
        axiz.security.issuers[0].audiences: ['${spring.application.name}']
        axiz.outbox.table: original_outbox
        axiz.http-client.clients.bank.oauth.client-id: old-oauth-client
        """);
    Path chart =
        Files.createDirectories(dir.resolve("deploy/helm/old-business/templates")).getParent();
    Files.writeString(
        chart.resolve("Chart.yaml"),
        "apiVersion: v2\nname: old-business\nversion: 0.1.0\nannotations.owner: payments-team\n");
    Files.writeString(
        chart.resolve("values.yaml"),
        """
        externalSecret.remotePrefix: dev/old-business
        config.axiz.messaging.consumer.name: existing-consumer
        config.axiz.security.issuers[0].audiences: [old-business]
        service.port: 80
        """);
    ProjectManifest manifest = manifest("old-business");
    ProjectRename.apply(dir, manifest, "payment-orders", true);
    assertFalse(Files.exists(dir.resolve(ProjectRename.LEDGER)), "dry run must not mutate files");
    assertTrue(Files.readString(resources.resolve("application.yaml")).contains("old-business"));
    assertTrue(Files.readString(chart.resolve("Chart.yaml")).contains("name: old-business"));
    ProjectRename.apply(dir, manifest, "payment-orders", false);
    assertTrue(ProjectRename.problems(dir).isEmpty());
    String text = Files.readString(resources.resolve("application.yaml"));
    for (String stable :
        List.of(
            "urn:custom:payments",
            "original_outbox",
            "old-oauth-client",
            "old-business",
            "payment-orders")) assertTrue(text.contains(stable), stable);
    String values = Files.readString(chart.resolve("values.yaml"));
    assertTrue(Files.readString(chart.resolve("Chart.yaml")).contains("name: payment-orders"));
    assertTrue(Files.readString(chart.resolve("Chart.yaml")).contains("payments-team"));
    for (String stable : List.of("dev/old-business", "existing-consumer", "old-business"))
      assertTrue(values.contains(stable), stable);
    assertTrue(
        Files.readString(chart.resolve("templates/migration-service-aliases.yaml"))
            .contains("service.selectorLabels"));
    ProjectRename.apply(dir, manifest.withName("payment-orders"), "payment-records", false);
    assertTrue(ProjectRename.problems(dir).isEmpty());
    assertTrue(
        Files.readString(resources.resolve("application.yaml")).contains("urn:custom:payments"));
    assertTrue(Files.readString(chart.resolve("values.yaml")).contains("payment-orders"));
    CapabilityAdder.mergeYaml(
        resources.resolve("application.yaml"),
        Map.of("axiz.messaging.consumer.name", "accidental-new-consumer"));
    assertTrue(ProjectRename.problems(dir).stream().anyMatch(s -> s.contains("consumer.name")));
  }

  @Test
  void legacyIntegrationNeedsExplicitDecisionAndMigrationKeepsPackage(@TempDir Path dir)
      throws Exception {
    Files.createDirectories(dir.resolve(".axiz"));
    Files.writeString(
        dir.resolve(ProjectManifest.PATH),
        """
        schema: 1
        name: core-integration
        groupId: pe.axiz.payments
        basePackage: pe.axiz.payments.coreintegration
        archetype: integration
        framework: hexagonal
        stack: servlet
        persistence: none
        axizVersion: 1.0.0
        """);
    assertThrows(IOException.class, () -> ProjectManifest.read(dir));
    ProjectManifest migrated = ProjectManifest.read(dir, "core").withName("banking-core");
    migrated.write(dir);
    assertEquals("core", ProjectManifest.read(dir).archetype());
    assertEquals("pe.axiz.payments.coreintegration", ProjectManifest.read(dir).basePackage());
    assertTrue(Files.readString(dir.resolve(ProjectManifest.PATH)).contains("schema: 2"));
  }

  private static ProjectManifest manifest(String name) {
    return new ProjectManifest(
        name,
        "pe.axiz.payments",
        "pe.axiz.payments.orders",
        "business",
        Structure.HEXAGONAL,
        Stack.SERVLET,
        "none",
        "2.0.0",
        null,
        null,
        List.of(),
        List.of());
  }
}
