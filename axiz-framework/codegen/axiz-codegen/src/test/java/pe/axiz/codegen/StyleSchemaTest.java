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
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Style;
import pe.axiz.codegen.output.GeneratedProject;

/**
 * A generated service starts with the tables of every store it wires, including the stores its
 * style starter brings without being chosen (an event processor on Aurora once failed at startup
 * because its inbox table was missing).
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

  /** Store starters a style starter may carry, and the table each one needs. */
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
  void anEventProcessorOnAuroraCreatesItsInboxAndOutboxTables() {
    for (Stack stack : List.of(Stack.SERVLET, Stack.REACTIVE)) {
      GeneratedProject p =
          generator.generate(
              Fixtures.request("event-processor", Framework.EDA, stack, "aurora", List.of()));
      String v1 = p.text(V1);
      assertTrue(v1.contains(TABLE_DDL.get("inbox")), stack + ": " + v1);
      // the style starter wires the JDBC outbox (the R2DBC one comes with the outbox capability)
      assertEquals(stack == Stack.SERVLET, v1.contains(TABLE_DDL.get("outbox")), stack.id());
    }
  }

  @Test
  void anEventProcessorOnDynamoDbDeduplicatesWithItsOwnTable() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "event-processor", Framework.EDA, Stack.SERVLET, "dynamodb", List.of()));
    assertTrue(p.text("src/main/resources/application.yaml").contains("store: dynamodb"));
    assertTrue(p.text("pom.xml").contains("<artifactId>axiz-core-inbox-dynamodb</artifactId>"));
    assertTrue(p.text("local/localstack/init.sh").contains("table axiz-inbox pk:S:HASH"));
  }

  @Test
  void anEventProcessorWithoutPersistenceHasNoInboxStore() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request("event-processor", Framework.EDA, Stack.SERVLET, "none", List.of()));
    assertFalse(p.files().containsKey(V1));
    assertFalse(p.text("src/main/resources/application.yaml").contains("axiz:\n  inbox:"));
  }

  /** Every combination: a style store on a JDBC database means its table is in V1. */
  @Test
  void everyWiredStoreHasItsTableInEveryCombination() {
    int checked = 0;
    for (Archetype a : catalog.archetypes()) {
      for (Framework f : a.frameworks()) {
        for (Stack s : a.stacks()) {
          for (String persistence : a.persistence()) {
            ProjectRequest r = Fixtures.request(a.name(), f, s, persistence, List.of());
            if (!"aurora".equals(persistence) || !generator.validate(r).valid()) {
              continue;
            }
            String v1 = generator.generate(r).text(V1);
            Set<String> expected = new TreeSet<>();
            if (s == Stack.SERVLET) {
              expected.addAll(catalog.style(f).jdbcTables());
            }
            for (String c : catalog.style(f).capabilities()) {
              if (Style.TABLES.contains(c)) {
                expected.add(c);
              }
            }
            for (String table : expected) {
              assertTrue(v1.contains(TABLE_DDL.get(table)), a.name() + "/" + f.id() + ": " + table);
              checked++;
            }
          }
        }
      }
    }
    assertTrue(checked >= 4, "checked " + checked);
  }

  /**
   * The model and the starters agree: every store starter a style starter declares is listed for
   * that style, and every listed one is really declared (read from the reactor, next to codegen).
   */
  @Test
  void stylesDeclareExactlyTheStoresTheirStartersCarry() throws IOException {
    Path frameworks = Path.of("../../frameworks").toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isDirectory(frameworks), "framework reactor not next to codegen");
    Pattern artifact = Pattern.compile("<artifactId>([\\w.-]+)</artifactId>");
    for (Framework f : Framework.values()) {
      if (f == Framework.NONE) {
        continue;
      }
      Path pom = frameworks.resolve(f.id()).resolve(f.starter()).resolve("pom.xml");
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
      Set<String> declared = new TreeSet<>(style.jdbcTables());
      style.capabilities().stream().filter(Style.TABLES::contains).forEach(declared::add);
      assertEquals(carried, declared, f.id() + " starter carries " + carried);
    }
  }

  /** Not optional and not test scoped: the dependency reaches the services of the style. */
  private static boolean carriedToServices(String xml, int from) {
    int end = xml.indexOf("</dependency>", from);
    String rest = end > 0 ? xml.substring(from, end) : "";
    return !rest.contains("<optional>true</optional>") && !rest.contains("<scope>test</scope>");
  }
}
