package pe.axiz.quality.archunit;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import pe.axiz.quality.archunit.selfcheck.application.UnsafeMoney;

/**
 * Confirms that the service's actual rule collection rejects a known prohibited fixture. This
 * catches empty/disconnected rule sets; it does not prove every individual architecture rule.
 */
public final class ArchitectureSelfCheck {
  private ArchitectureSelfCheck() {}

  public static void verify(AxizArchitecture architecture) {
    verify(architecture.rules());
  }

  public static void verify(List<ArchRule> rules) {
    var fixture = new ClassFileImporter().importClasses(UnsafeMoney.class);
    boolean rejected =
        rules.stream()
            .anyMatch(rule -> rule.allowEmptyShould(true).evaluate(fixture).hasViolation());
    if (!rejected)
      throw new AssertionError(
          "Architecture rules did not reject the prohibited self-check fixture");
  }
}
