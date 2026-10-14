package pe.axiz.quality.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for service tests:
 *
 * <pre>{@code
 * @Test
 * void architecture() {
 *   AxizArchitecture.verify("pe.axiz.payments.business", AxizArchitecture.Style.HEXAGONAL);
 * }
 * }</pre>
 */
public final class AxizArchitecture {

  /** Architectural styles (layer 3 frameworks). */
  public enum Style {
    LAYERED,
    HEXAGONAL,
    CQRS,
    EDA,
    BFF,
    BATCH;

    public static Style parse(String value) {
      return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
  }

  private AxizArchitecture() {}

  public static List<ArchRule> rules(String basePackage, Style style) {
    List<ArchRule> rules = new ArrayList<>(GeneralRules.all());
    rules.addAll(
        switch (style) {
          case LAYERED -> LayeredRules.rules(basePackage);
          case HEXAGONAL -> HexagonalRules.rules(basePackage);
          case CQRS -> CqrsRules.rules(basePackage);
          case EDA -> EdaRules.rules(basePackage);
          case BFF -> BffRules.rules(basePackage);
          case BATCH -> BatchRules.rules(basePackage);
        });
    return rules;
  }

  /**
   * Imports production classes of the package and checks every rule, failing with all violations.
   */
  public static void verify(String basePackage, Style style) {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(basePackage);
    List<String> failures = new ArrayList<>();
    for (ArchRule rule : rules(basePackage, style)) {
      var result = rule.allowEmptyShould(true).evaluate(classes);
      if (result.hasViolation()) {
        failures.add(result.getFailureReport().toString());
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }
}
