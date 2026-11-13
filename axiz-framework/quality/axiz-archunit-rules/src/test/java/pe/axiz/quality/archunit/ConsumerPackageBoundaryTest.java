package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.archfixture.application.ForeignLeak;
import com.bank.archfixture.application.LocalTypes;
import com.bank.archfixture.application.SqlLeak;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ConsumerPackageBoundaryTest {
  private static ArchRule boundary() {
    return HexagonalRules.rules("com.bank.archfixture").stream()
        .filter(rule -> rule.getDescription().contains("through Axiz contracts"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void consumerOwnedRecordsAndGenericArgumentsDoNotNeedTheAxizNamespace() {
    var classes =
        new ClassFileImporter()
            .importClasses(LocalTypes.class, LocalTypes.Input.class, LocalTypes.Values.class);
    assertFalse(boundary().evaluate(classes).hasViolation());
  }

  @Test
  void owningThePackageDoesNotPermitInfrastructureDependencies() {
    var result = boundary().evaluate(new ClassFileImporter().importClasses(SqlLeak.class));
    assertTrue(result.hasViolation());
    assertTrue(result.getFailureReport().toString().contains("java.sql.Connection"));
  }

  @Test
  void aSimilarPackagePrefixDoesNotBecomeAnApprovedDependency() {
    var result = boundary().evaluate(new ClassFileImporter().importClasses(ForeignLeak.class));
    assertTrue(result.hasViolation());
    assertTrue(result.getFailureReport().toString().contains("com.bank.archfixturex.ForeignType"));
  }
}
