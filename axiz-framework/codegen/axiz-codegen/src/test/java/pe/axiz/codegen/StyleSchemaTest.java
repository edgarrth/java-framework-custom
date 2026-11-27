package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.model.Style;
import pe.axiz.codegen.output.GeneratedProject;

/**
 * Un servicio generado arranca con las tablas de cada almacén que cablea, incluidos los almacenes
 * que aporta el starter de su estilo sin haberlos elegido (sin esto, un procesador de eventos sobre
 * Aurora falla al arrancar por no tener su tabla inbox).
 */
class StyleSchemaTest {

  private static final String V1 = "src/main/resources/db/migration/V1__init.sql";
  private static final Map<String, String> TABLE_DDL =
      Map.of(
          "outbox", "CREATE TABLE IF NOT EXISTS axiz_outbox",
          "inbox", "CREATE TABLE IF NOT EXISTS axiz_inbox",
          "saga", "CREATE TABLE IF NOT EXISTS axiz_saga",
          "sequence", "CREATE TABLE IF NOT EXISTS axiz_sequence",
          "shedlock", "CREATE TABLE IF NOT EXISTS shedlock");

  /**
   * Starters de almacén que puede incluir un starter de estilo y la tabla que necesita cada uno.
   */
  private static final Map<String, String> STORE_STARTERS =
      Map.of(
          "axiz-inbox-spring-boot-starter", "inbox",
          "axiz-outbox-spring-boot-autoconfigure", "outbox",
          "axiz-outbox-spring-boot-starter", "outbox",
          "axiz-saga-spring-boot-starter", "saga",
          "axiz-sequence-spring-boot-starter", "sequence",
          "axiz-scheduling-spring-boot-starter", "shedlock");

  private final ProjectGenerator generator = ProjectGenerator.withDefaults();
  private final Catalog catalog = generator.catalog();

  @Test
  void anEventProcessorOnAuroraCreatesOnlyTheSelectedStoreTables() {
    for (Stack stack : List.of(Stack.SERVLET, Stack.REACTIVE)) {
      GeneratedProject p =
          generator.generate(
              Fixtures.request("event-processor", Structure.EDA, stack, "aurora", List.of()));
      String v1 = p.text(V1);
      assertTrue(v1.contains(TABLE_DDL.get("inbox")), stack + ": " + v1);
      assertFalse(v1.contains(TABLE_DDL.get("outbox")), stack.id());
      assertTrue(
          p.text("pom.xml")
              .contains(
                  "axiz-inbox-"
                      + (stack == Stack.SERVLET ? "jdbc" : "r2dbc")
                      + "-spring-boot-starter"));
      assertFalse(p.text("pom.xml").contains("axiz-outbox"));
      var withOutbox =
          generator.generate(
              Fixtures.request(
                  "event-processor", Structure.EDA, stack, "aurora", List.of("outbox")));
      assertTrue(withOutbox.text(V1).contains(TABLE_DDL.get("outbox")));
    }
  }

  @Test
  void anEventProcessorOnDynamoDbDeduplicatesWithItsOwnTable() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "event-processor", Structure.EDA, Stack.SERVLET, "dynamodb", List.of()));
    assertTrue(p.text("src/main/resources/application.yaml").contains("store: dynamodb"));
    assertTrue(
        p.text("pom.xml")
            .contains("<artifactId>axiz-inbox-dynamodb-spring-boot-starter</artifactId>"));
    assertTrue(p.text("local/localstack/init.sh").contains("table axiz-inbox pk:S:HASH"));
  }

  @Test
  void anEventProcessorWithoutPersistenceHasNoInboxStore() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request("event-processor", Structure.EDA, Stack.SERVLET, "none", List.of()));
    assertFalse(p.files().containsKey(V1));
    assertFalse(p.text("src/main/resources/application.yaml").contains("axiz:\n  inbox:"));
    assertFalse(p.text("pom.xml").contains("axiz-inbox"));
    assertFalse(p.text("pom.xml").contains("dynamodb"));
    assertFalse(p.files().containsKey("local/localstack/init.sh"));
  }

  /**
   * Cada combinación: un almacén del estilo sobre una base de datos JDBC implica que su tabla está
   * en V1.
   */
  @Test
  void everyWiredStoreHasItsTableInEveryCombination() {
    Set<Structure> checked = new TreeSet<>();
    for (Archetype a : catalog.archetypes()) {
      for (Structure f : a.structures()) {
        for (Stack s : a.stacks()) {
          for (String persistence : a.persistence()) {
            ProjectRequest r = Fixtures.request(a.name(), f, s, persistence, List.of());
            if (!"aurora".equals(persistence) || !generator.validate(r).valid()) {
              continue;
            }
            String v1 = generator.generate(r).text(V1);
            var capabilities =
                catalog
                    .resolve(
                        ProjectGenerator.capabilityNames(catalog, r, a),
                        Catalog.store(persistence, s))
                    .stream()
                    .map(pe.axiz.codegen.model.Capability::name)
                    .toList();
            for (String table : Style.TABLES) {
              String capability = table.equals("shedlock") ? "scheduling" : table;
              assertEquals(
                  capabilities.contains(capability),
                  v1.contains(TABLE_DDL.get(table)),
                  a.name() + "/" + f.id() + "/" + s + ": " + table);
            }
            checked.add(f);
          }
        }
      }
    }
    assertTrue(
        checked.containsAll(
            Set.of(Structure.EDA, Structure.BATCH, Structure.HEXAGONAL, Structure.LAYERED)),
        "checked " + checked);
  }

  /**
   * El modelo y los starters coinciden: cada starter de almacén que declara un starter de estilo
   * figura para ese estilo. Las dependencias opcionales de almacén solo se activan mediante una
   * capacidad explícita.
   */
  @Test
  void stylesDeclareEveryMandatoryStoreTheirStartersCarry() throws IOException {
    Path structures = Path.of("../../structures").toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isDirectory(structures), "structure reactor not next to codegen");
    Pattern artifact = Pattern.compile("<artifactId>([\\w.-]+)</artifactId>");
    for (Structure f : Structure.values()) {
      if (f == Structure.NONE) {
        continue;
      }
      Path pom = structures.resolve(f.id()).resolve(f.starter()).resolve("pom.xml");
      String xml = Files.readString(pom, StandardCharsets.UTF_8);
      String dependencies = xml.substring(xml.indexOf("<dependencies>"));
      Set<String> carried = new TreeSet<>();
      Matcher m = artifact.matcher(dependencies);
      while (m.find()) {
        String table = STORE_STARTERS.get(m.group(1));
        if (table != null && carriedToServices(dependencies, m.start())) {
          carried.add(table);
        }
      }
      Style style = catalog.style(f);
      Set<String> declared = new TreeSet<>();
      style.capabilities().stream()
          .map(name -> name.equals("scheduling") ? "shedlock" : name)
          .filter(Style.TABLES::contains)
          .forEach(declared::add);
      assertTrue(
          declared.containsAll(carried), f.id() + " has an undeclared mandatory store: " + carried);
    }
  }

  /** Ni opcional ni con scope de test: la dependencia llega a los servicios del estilo. */
  private static boolean carriedToServices(String xml, int from) {
    int end = xml.indexOf("</dependency>", from);
    String rest = end > 0 ? xml.substring(from, end) : "";
    return !rest.contains("<optional>true</optional>") && !rest.contains("<scope>test</scope>");
  }
}
