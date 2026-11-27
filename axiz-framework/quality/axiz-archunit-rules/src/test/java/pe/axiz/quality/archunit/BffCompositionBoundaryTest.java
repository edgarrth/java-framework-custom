package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.sample.bff.controller.Routes;

class BffCompositionBoundaryTest {
  @Test
  void controllersComposeClientsAndViewsWithoutImportingBootstrapConfiguration() {
    var good = new ClassFileImporter().importClasses(Routes.Good.class);
    var bad = new ClassFileImporter().importClasses(Routes.Bad.class);
    var rules = BffRules.rules("pe.axiz.quality.archunit.sample.bff");
    assertFalse(
        rules.stream().anyMatch(rule -> rule.allowEmptyShould(true).evaluate(good).hasViolation()));
    assertTrue(
        rules.stream().anyMatch(rule -> rule.allowEmptyShould(true).evaluate(bad).hasViolation()));
  }
}
