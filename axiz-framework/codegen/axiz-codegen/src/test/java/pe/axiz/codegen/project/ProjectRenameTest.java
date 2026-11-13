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
  void preservesBootstrapIdentityOverridesAndInheritanceAndDetectsDrift(@TempDir Path dir)
      throws Exception {
    Path resources = Files.createDirectories(dir.resolve("src/main/resources"));
    Files.writeString(
        resources.resolve("application.yaml"), "spring.application.name: old-business\n");
    Path chart = Files.createDirectories(dir.resolve("deploy/helm/old-business"));
    Files.writeString(
        chart.resolve("Chart.yaml"), "apiVersion: v2\nname: old-business\nversion: 2.0.0\n");
    Path base = chart.resolve("values.yaml");
    Files.writeString(
        base,
        "configBootstrap.parameterStore.enabled: true\nconfigBootstrap.parameterStore.application: stable-payments\n");
    Path qa = chart.resolve("values-qa.yaml");
    Files.writeString(qa, "configBootstrap.parameterStore.application: stable-qa\n");
    Path prod = chart.resolve("values-prod.yaml");
    Files.writeString(prod, "replicaCount: 3\n");
    String before = Files.readString(base);
    ProjectRename.apply(dir, manifest("old-business"), "payment-orders", true);
    assertEquals(before, Files.readString(base));
    assertFalse(Files.exists(dir.resolve(ProjectRename.LEDGER)));
    ProjectRename.apply(dir, manifest("old-business"), "payment-orders", false);
    assertTrue(ProjectRename.problems(dir).isEmpty());
    assertTrue(Files.readString(base).contains("stable-payments"));
    assertTrue(Files.readString(qa).contains("stable-qa"));
    assertFalse(
        Files.readString(resources.resolve("application.yaml")).contains("parameter-store"));
    assertFalse(
        Files.readString(prod).contains("parameterStore"), "inheritance is not materialized");
    CapabilityAdder.mergeYaml(
        prod, Map.of("configBootstrap.parameterStore.application", "accidental"));
    assertTrue(
        ProjectRename.problems(dir).stream()
            .anyMatch(problem -> problem.contains("values-prod.yaml")));
    Files.writeString(prod, "replicaCount: 3\n");
    Files.writeString(qa, "replicaCount: 2\n");
    assertTrue(
        ProjectRename.problems(dir).stream()
            .anyMatch(problem -> problem.contains("values-qa.yaml")));
  }

  @Test
  void rejectsMissingOrAmbiguousEnabledBootstrapBeforeWriting(@TempDir Path temporary)
      throws Exception {
    for (String selector :
        List.of("", "configBootstrap.parameterStore.application: '${spring.application.name}'\n")) {
      Path dir = Files.createTempDirectory(temporary, "bootstrap-");
      Path resources = Files.createDirectories(dir.resolve("src/main/resources"));
      Path application = resources.resolve("application.yaml");
      String before = "spring.application.name: old-business\n";
      Files.writeString(application, before);
      Path chart = Files.createDirectories(dir.resolve("deploy/helm/old-business"));
      Files.writeString(
          chart.resolve("Chart.yaml"), "apiVersion: v2\nname: old-business\nversion: 2.0.0\n");
      Files.writeString(
          chart.resolve("values.yaml"),
          "configBootstrap.parameterStore.enabled: true\n" + selector);
      assertThrows(
          IOException.class,
          () -> ProjectRename.apply(dir, manifest("old-business"), "payment-orders", false));
      assertEquals(before, Files.readString(application));
      assertFalse(Files.exists(dir.resolve(ProjectRename.LEDGER)));
    }
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
