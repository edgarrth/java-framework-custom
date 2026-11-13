package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.render.TemplateSet;

class CanonicalSchemaTest {
  @Test
  void initialAndAddedCapabilityFragmentsUseTheSameCanonicalBytes() throws Exception {
    var templates = TemplateSet.load("service");
    for (String schema :
        new String[] {"outbox", "inbox", "saga", "sequence", "shedlock", "batch"}) {
      String canonical;
      try (var stream =
          Objects.requireNonNull(
              getClass().getResourceAsStream("/axiz/sql/" + schema + "-postgresql.sql"))) {
        canonical = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      assertEquals(
          canonical, templates.renderTemplate("partials/sql/" + schema + ".mustache", Map.of()));
      var context =
          Map.<String, Object>of(
              "project", Map.of("name", "fixture"),
              "schema", Map.of(schema, true),
              "fw", Map.of("batch", schema.equals("batch")),
              "persistence", Map.of("jpa", true));
      String migration = templates.renderTemplate("resources/V1__init.sql.mustache", context);
      assertTrue(migration.contains(canonical));
      assertEquals(
          schema.equals("outbox"), migration.contains("CREATE TABLE IF NOT EXISTS axiz_outbox"));
    }
  }
}
