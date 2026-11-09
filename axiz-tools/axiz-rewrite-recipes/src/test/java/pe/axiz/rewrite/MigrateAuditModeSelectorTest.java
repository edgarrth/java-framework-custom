package pe.axiz.rewrite;

import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.yaml.Assertions.yaml;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RewriteTest;

class MigrateAuditModeSelectorTest implements RewriteTest {
  private static final String TODO =
      "# TODO axiz-2.0: migrar audit selector a axiz.audit.mode manualmente\n";

  @Test
  void durableWinsOverAsyncWithoutLosingQueueOptions() {
    rewriteRun(
        spec ->
            spec.recipe(new MigrateAuditModeSelector()).cycles(2).expectedCyclesThatMakeChanges(1),
        properties(
            "axiz.audit.durable.enabled=true\naxiz.audit.async.enabled=true\naxiz.audit.async.capacity=32\n",
            "axiz.audit.mode=DURABLE\naxiz.audit.async.capacity=32\n"));
  }

  @Test
  void explicitModeAndUnrelatedSettingsArePreserved() {
    rewriteRun(
        spec -> spec.recipe(new MigrateAuditModeSelector()),
        properties(
            "axiz.audit.mode=ASYNC\naxiz.audit.durable.enabled=false\naxiz.audit.async.enabled=true\naxiz.audit.async.offer-timeout=50ms\n",
            "axiz.audit.mode=ASYNC\naxiz.audit.async.offer-timeout=50ms\n"));
  }

  @Test
  void disablingAsyncPreservesSynchronousDelivery() {
    rewriteRun(
        spec -> spec.recipe(new MigrateAuditModeSelector()),
        properties("axiz.audit.async.enabled=false\n", "axiz.audit.mode=SYNC\n"));
  }

  @Test
  void nestedYamlMigratesOneEffectiveSelector() {
    rewriteRun(
        spec -> spec.recipe(new MigrateAuditModeSelector()),
        yaml(
            """
        axiz:
          audit:
            durable:
              enabled: true
        """,
            """
        axiz:
          audit:
            mode: DURABLE
        """));
  }

  @Test
  void placeholdersAndConflictsAreMarkedWithoutChangingTheirBehavior() {
    String placeholder = "axiz.audit.durable.enabled=${DURABLE:false}\n";
    rewriteRun(
        spec ->
            spec.recipe(new MigrateAuditModeSelector()).cycles(2).expectedCyclesThatMakeChanges(1),
        properties(placeholder, TODO + placeholder));
    String conflict = "axiz.audit.mode=ASYNC\naxiz.audit.durable.enabled=true\n";
    rewriteRun(
        spec -> spec.recipe(new MigrateAuditModeSelector()), properties(conflict, TODO + conflict));
  }

  @Test
  void profileCompositionIsScannedBeforeEitherFileIsModified() {
    String base = "axiz.audit.durable.enabled=true\n";
    String profile = "axiz.audit.async.enabled=true\n";
    rewriteRun(
        spec ->
            spec.recipe(new MigrateAuditModeSelector()).cycles(2).expectedCyclesThatMakeChanges(1),
        properties(base, TODO + base, s -> s.path("src/main/resources/application.properties")),
        properties(
            profile, TODO + profile, s -> s.path("src/main/resources/application-qa.properties")));
  }

  @Test
  void explicitImportsAreNotTreatedAsAnIsolatedConfiguration() {
    String source =
        "spring.config.import=optional:configserver:http://config\naxiz.audit.async.enabled=true\n";
    rewriteRun(
        spec -> spec.recipe(new MigrateAuditModeSelector()), properties(source, TODO + source));
  }

  @Test
  void anAlreadyMigratedDocumentIsANoop() {
    rewriteRun(
        spec -> spec.recipe(new MigrateAuditModeSelector()), yaml("axiz.audit.mode: DURABLE\n"));
  }

  @Test
  void canonicalModeInAnotherSourceDoesNotGetOverwrittenByANewSelector() {
    String legacy = "axiz.audit.durable.enabled=true\n";
    rewriteRun(
        spec -> spec.recipe(new MigrateAuditModeSelector()),
        properties(legacy, TODO + legacy, s -> s.path("src/main/resources/application.properties")),
        yaml("axiz.audit.mode: ASYNC\n", s -> s.path("src/main/resources/application.yaml")));
  }

  @Test
  void multipleYamlDocumentsKeepTheirBoundariesAndSelectors() {
    rewriteRun(
        spec ->
            spec.recipe(new MigrateAuditModeSelector()).cycles(2).expectedCyclesThatMakeChanges(1),
        yaml(
            "axiz.audit.durable.enabled: true\n---\nspring.config.activate.on-profile: qa\naxiz.audit.async.enabled: true\n",
            TODO
                + "axiz.audit.durable.enabled: true\n\n"
                + TODO
                + "---\nspring.config.activate.on-profile: qa\naxiz.audit.async.enabled: true\n"));
  }
}
