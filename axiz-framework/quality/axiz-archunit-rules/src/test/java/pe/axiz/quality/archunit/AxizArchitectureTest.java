package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class AxizArchitectureTest {

  private final JavaClasses sample =
      new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.hex");

  @Test
  void hexagonalRulesDetectOutwardDependencies() {
    ArchRule layering = HexagonalRules.rules("pe.axiz.quality.archunit.sample.hex").get(0);
    assertTrue(layering.evaluate(sample).hasViolation());
    String report = layering.evaluate(sample).getFailureReport().toString();
    assertTrue(report.contains("BadDomain"), report);
  }

  @Test
  void everyStyleHasRules() {
    for (AxizArchitecture.Style style : AxizArchitecture.Style.values()) {
      assertFalse(AxizArchitecture.rules("pe.axiz.x", style).isEmpty());
    }
    assertEquals(AxizArchitecture.Style.CQRS, AxizArchitecture.Style.parse(" cqrs "));
  }

  @Test
  void aComponentSharingItsNameWithABeanIsReported() {
    ArchRule rule =
        GeneralRules.uniqueBeanNames(
            "pe.axiz.quality.archunit.sample.beans.Component",
            "pe.axiz.quality.archunit.sample.beans.Bean",
            "pe.axiz.quality.archunit.sample.beans.AutoConfiguration");
    String report =
        rule.evaluate(
                new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.beans"))
            .getFailureReport()
            .toString();
    assertTrue(report.contains("bean name 'settlementJob' is defined by"), report);
    assertTrue(report.contains("@Bean SettlementJob.settlementJob()"), report);
    assertTrue(report.contains("bean name 'payments'"), report);
    // explicit names, auto-configurations (registered by class name) and clean classes pass
    assertFalse(report.contains("'stepsConfiguration'"), report);
    assertFalse(report.contains("'clean'"), report);
    assertFalse(report.contains("jobsAutoConfiguration"), report);
    assertEquals(
        2,
        rule.evaluate(
                new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.beans"))
            .getFailureReport()
            .getDetails()
            .size(),
        report);
  }

  @Test
  void generalRulesPassOnCleanCode() {
    for (ArchRule rule : GeneralRules.all()) {
      assertFalse(
          rule.allowEmptyShould(true)
              .evaluate(
                  new ClassFileImporter()
                      .importPackages("pe.axiz.quality.archunit.sample.hex.application"))
              .hasViolation(),
          rule.getDescription());
    }
  }
}
