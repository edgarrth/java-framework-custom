package pe.axiz.codegen.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.Fixtures;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

class CapabilityAdderTest {

  private final ProjectGenerator generator = ProjectGenerator.withDefaults();
  private final CapabilityAdder adder = new CapabilityAdder(generator.catalog());

  private Path project(Path dir, String archetype, Structure f, Stack s, String persistence)
      throws Exception {
    generator
        .generate(Fixtures.request(archetype, f, s, persistence, List.of()))
        .writeTo(dir, false);
    return dir;
  }

  @Test
  void addingOutboxPreservesTheExistingExplicitAuditMode(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora");
    Path application = dir.resolve("src/main/resources/application.yaml");
    assertTrue(Files.readString(application).contains("mode: ASYNC"));
    adder.add(dir, "outbox");
    assertTrue(Files.readString(application).contains("mode: ASYNC"));
  }

  @Test
  void addingAuditToAnExistingOutboxSelectsDurableAndPreservesPreviousSelectors(@TempDir Path tmp)
      throws Exception {
    Path fresh =
        project(tmp.resolve("fresh"), "support", Structure.HEXAGONAL, Stack.SERVLET, "aurora");
    adder.add(fresh, "outbox");
    adder.add(fresh, "audit");
    assertTrue(
        Files.readString(fresh.resolve("src/main/resources/application.yaml"))
            .contains("mode: DURABLE"));
    int n = 0;
    for (String selector :
        List.of(
            "axiz.audit.mode: SYNC",
            "axiz:\n  audit:\n    mode: ASYNC",
            "axiz:\n  audit.mode: SYNC")) {
      Path existing =
          project(
              tmp.resolve("existing-" + n++),
              "support",
              Structure.HEXAGONAL,
              Stack.SERVLET,
              "aurora");
      adder.add(existing, "outbox");
      Path application = existing.resolve("src/main/resources/application.yaml");
      Files.writeString(application, selector + "\n");
      adder.add(existing, "audit");
      String updated = Files.readString(application);
      assertFalse(updated.contains("DURABLE"), updated);
      assertTrue(updated.contains(selector), updated);
    }
  }

  @Test
  void addingAuditPreservesSelectorsDeclaredOnlyInProfiles(@TempDir Path tmp) throws Exception {
    int n = 0;
    for (String file : List.of("application-prod.yaml", "application-prod.properties")) {
      Path dir =
          project(
              tmp.resolve("profile-" + n++),
              "support",
              Structure.HEXAGONAL,
              Stack.SERVLET,
              "aurora");
      adder.add(dir, "outbox");
      Path profile = dir.resolve("src/main/resources/" + file);
      String selector =
          file.endsWith(".properties")
              ? "axiz.audit.mode=SYNC\n"
              : "axiz:\n  audit:\n    mode: SYNC\n";
      Files.writeString(profile, selector);
      adder.add(dir, "audit");
      assertEquals(selector, Files.readString(profile));
      assertFalse(
          Files.readString(dir.resolve("src/main/resources/application.yaml"))
              .contains("mode: DURABLE"));
    }
  }

  @Test
  void addsStarterAdaptersPropertiesKeysAndManifest(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora");
    CapabilityAdder.Result r = adder.add(dir, "idempotency-redis");
    assertEquals(List.of("idempotency", "idempotency-redis"), r.added());
    String pom = Files.readString(dir.resolve("pom.xml"));
    assertTrue(pom.contains("<artifactId>axiz-idempotency-spring-boot-starter</artifactId>"));
    assertTrue(pom.contains("<artifactId>axiz-adapter-idempotency-redis</artifactId>"));
    assertTrue(pom.contains("<artifactId>spring-boot-starter-data-redis</artifactId>"));
    assertEquals(
        1, pom.split("axiz-idempotency-spring-boot-starter").length - 1, "starter added once");
    String app = Files.readString(dir.resolve("src/main/resources/application.yaml"));
    assertTrue(app.startsWith("# "), "header comment kept");
    assertTrue(app.contains("store: redis"));
    assertTrue(app.contains("name: orders"), "existing properties kept");
    assertTrue(
        Files.readString(dir.resolve("deploy/helm/orders/values-prod.yaml"))
            .contains("#   spring.data.redis.url:"));
    assertTrue(ProjectManifest.read(dir).capabilities().contains("idempotency-redis"));
    assertTrue(r.example().contains("axiz.idempotency.store"));

    CapabilityAdder.Result again = adder.add(dir, "idempotency-redis");
    assertTrue(again.added().isEmpty(), "idempotent");
  }

  @Test
  void addsTheTablesAsANewMigration(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora");
    CapabilityAdder.Result r = adder.add(dir, "outbox");
    // continues the project numbering after V1__init.sql
    Path migration = dir.resolve("src/main/resources/db/migration/V2__axiz_outbox.sql");
    assertTrue(Files.exists(migration), r.changed().toString());
    assertTrue(Files.readString(migration).contains("CREATE TABLE IF NOT EXISTS axiz_outbox"));
    assertEquals("3", Migrations.next(dir));
  }

  @Test
  void migrationVersionsStartAtOneAndIgnoreOtherFiles(@TempDir Path tmp) throws Exception {
    assertEquals("1", Migrations.next(tmp));
    Path m = tmp.resolve(Migrations.DIR);
    Files.createDirectories(m);
    Files.writeString(m.resolve("V9__a.sql"), "");
    Files.writeString(m.resolve("V10__b.sql"), "");
    Files.writeString(m.resolve("R__view.sql"), "");
    Files.writeString(m.resolve("notes.md"), "");
    assertEquals("11", Migrations.next(tmp));
  }

  @Test
  void addsSecretsToTheExternalSecret(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "core", Structure.HEXAGONAL, Stack.SERVLET, "none");
    adder.add(dir, "crypto");
    String values = Files.readString(dir.resolve("deploy/helm/orders-core/values.yaml"));
    assertTrue(values.contains("    - field-key-v1\n    - blind-index-key"), values);
    assertFalse(values.contains("    []"));
  }

  @Test
  void rejectsIncompatibleCapabilities(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Structure.HEXAGONAL, Stack.REACTIVE, "dynamodb");
    assertThrows(IllegalArgumentException.class, () -> adder.add(dir, "outbox"));
    assertThrows(IllegalArgumentException.class, () -> adder.add(dir, "nope"));
    assertFalse(
        Files.readString(dir.resolve("pom.xml")).contains("axiz-outbox"), "nothing changed");
  }

  @Test
  void storeFollowsThePersistence(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "event-processor", Structure.EDA, Stack.SERVLET, "dynamodb");
    adder.add(dir, "sequence");
    assertTrue(Files.readString(dir.resolve("pom.xml")).contains("axiz-adapter-sequence-dynamodb"));
    assertTrue(
        Files.readString(dir.resolve("src/main/resources/application.yaml"))
            .contains("store: dynamodb"));
  }

  @Test
  void reactiveAuroraProjectsGetTheR2dbcVariants(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Structure.HEXAGONAL, Stack.REACTIVE, "aurora");
    CapabilityAdder.Result r = adder.add(dir, "outbox");
    assertEquals(List.of("outbox"), r.added(), "r2dbc came with the project");
    String pom = Files.readString(dir.resolve("pom.xml"));
    assertTrue(pom.contains("axiz-adapter-outbox-r2dbc"));
    assertFalse(pom.contains("axiz-jpa-spring-boot-starter"));
    assertTrue(
        Files.list(dir.resolve(Migrations.DIR))
            .anyMatch(f -> f.getFileName().toString().endsWith("__axiz_outbox.sql")));
    adder.add(dir, "scheduling");
    assertTrue(Files.readString(dir.resolve("pom.xml")).contains("shedlock-provider-r2dbc"));
    IllegalArgumentException jpa =
        assertThrows(IllegalArgumentException.class, () -> adder.add(dir, "saga"));
    assertTrue(jpa.getMessage().contains("does not support reactive"), jpa.getMessage());
    adder.add(dir, "sequence");
    String withSequence = Files.readString(dir.resolve("pom.xml"));
    assertTrue(
        withSequence.contains(
            "<artifactId>axiz-adapter-sequence-r2dbc</artifactId>\n      <scope>runtime</scope>"));
    assertFalse(withSequence.contains("axiz-adapter-sequence-jdbc"));
  }

  @Test
  void addedAdaptersMustBeRuntimeDependencies(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora");
    adder.add(dir, "outbox");
    String pom = Files.readString(dir.resolve("pom.xml"));
    assertTrue(
        pom.contains(
            "<artifactId>axiz-adapter-outbox-jdbc</artifactId>\n      <scope>runtime</scope>"),
        pom);
  }
}
