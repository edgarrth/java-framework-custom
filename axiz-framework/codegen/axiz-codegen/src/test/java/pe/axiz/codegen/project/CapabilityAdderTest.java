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
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.Stack;

class CapabilityAdderTest {

  private final ProjectGenerator generator = ProjectGenerator.withDefaults();
  private final CapabilityAdder adder = new CapabilityAdder(generator.catalog());

  private Path project(Path dir, String archetype, Framework f, Stack s, String persistence)
      throws Exception {
    generator
        .generate(Fixtures.request(archetype, f, s, persistence, List.of()))
        .writeTo(dir, false);
    return dir;
  }

  @Test
  void addsStarterAdaptersPropertiesKeysAndManifest(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Framework.HEXAGONAL, Stack.SERVLET, "aurora");
    CapabilityAdder.Result r = adder.add(dir, "idempotency-redis");
    assertEquals(List.of("idempotency", "idempotency-redis"), r.added());
    String pom = Files.readString(dir.resolve("pom.xml"));
    assertTrue(pom.contains("<artifactId>axiz-idempotency-spring-boot-starter</artifactId>"));
    assertTrue(pom.contains("<artifactId>axiz-core-idempotency-redis</artifactId>"));
    assertTrue(pom.contains("<artifactId>spring-boot-starter-data-redis</artifactId>"));
    assertEquals(
        1, pom.split("axiz-idempotency-spring-boot-starter").length - 1, "starter added once");
    String app = Files.readString(dir.resolve("src/main/resources/application.yaml"));
    assertTrue(app.startsWith("# "), "header comment kept");
    assertTrue(app.contains("store: redis"));
    assertTrue(app.contains("name: orders-business"), "existing properties kept");
    assertTrue(
        Files.readString(dir.resolve("deploy/helm/orders-business/values-prod.yaml"))
            .contains("#   spring.data.redis.url:"));
    assertTrue(ProjectManifest.read(dir).capabilities().contains("idempotency-redis"));
    assertTrue(r.example().contains("axiz.idempotency.store"));

    CapabilityAdder.Result again = adder.add(dir, "idempotency-redis");
    assertTrue(again.added().isEmpty(), "idempotent");
  }

  @Test
  void addsTheTablesAsANewMigration(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Framework.HEXAGONAL, Stack.SERVLET, "aurora");
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
    Path dir = project(tmp, "integration", Framework.HEXAGONAL, Stack.SERVLET, "none");
    adder.add(dir, "crypto");
    String values = Files.readString(dir.resolve("deploy/helm/orders-integration/values.yaml"));
    assertTrue(values.contains("    - field-key-v1\n    - blind-index-key"), values);
    assertFalse(values.contains("    []"));
  }

  @Test
  void rejectsIncompatibleCapabilities(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Framework.CQRS, Stack.REACTIVE, "dynamodb");
    assertThrows(IllegalArgumentException.class, () -> adder.add(dir, "outbox"));
    assertThrows(IllegalArgumentException.class, () -> adder.add(dir, "nope"));
    assertFalse(
        Files.readString(dir.resolve("pom.xml")).contains("axiz-outbox"), "nothing changed");
  }

  @Test
  void storeFollowsThePersistence(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "event-processor", Framework.EDA, Stack.SERVLET, "dynamodb");
    adder.add(dir, "sequence");
    assertTrue(Files.readString(dir.resolve("pom.xml")).contains("axiz-core-sequence-dynamodb"));
    assertTrue(
        Files.readString(dir.resolve("src/main/resources/application.yaml"))
            .contains("store: dynamodb"));
  }

  @Test
  void reactiveAuroraProjectsGetTheR2dbcVariants(@TempDir Path tmp) throws Exception {
    Path dir = project(tmp, "business", Framework.CQRS, Stack.REACTIVE, "aurora");
    CapabilityAdder.Result r = adder.add(dir, "outbox");
    assertEquals(List.of("outbox"), r.added(), "r2dbc came with the project");
    String pom = Files.readString(dir.resolve("pom.xml"));
    assertTrue(pom.contains("axiz-core-outbox-r2dbc"));
    assertFalse(pom.contains("axiz-jpa-spring-boot-starter"));
    assertTrue(
        Files.list(dir.resolve(Migrations.DIR))
            .anyMatch(f -> f.getFileName().toString().endsWith("__axiz_outbox.sql")));
    adder.add(dir, "scheduling");
    assertTrue(Files.readString(dir.resolve("pom.xml")).contains("shedlock-provider-r2dbc"));
    IllegalArgumentException jpa =
        assertThrows(IllegalArgumentException.class, () -> adder.add(dir, "saga"));
    assertTrue(jpa.getMessage().contains("not available on the reactive stack"), jpa.getMessage());
    assertThrows(IllegalArgumentException.class, () -> adder.add(dir, "sequence"));
  }
}
