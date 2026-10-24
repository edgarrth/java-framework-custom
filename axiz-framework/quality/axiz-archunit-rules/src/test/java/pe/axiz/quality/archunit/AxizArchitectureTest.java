package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  @Test
  void jvmLocalLocksInBusinessCodeAreReported() {
    JavaClasses locks =
        new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.locks");
    String lockReport =
        GeneralRules.NO_JVM_LOCKS_IN_BUSINESS_CODE.evaluate(locks).getFailureReport().toString();
    assertTrue(lockReport.contains("ReentrantLock"), lockReport);
    String syncReport =
        GeneralRules.NO_SYNCHRONIZED_BUSINESS_METHODS.evaluate(locks).getFailureReport().toString();
    assertTrue(syncReport.contains("credit()"), syncReport);
  }

  @Test
  void blockingCallsAndGlobalHooksAreReported() {
    JavaClasses reactive =
        new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.reactive");
    String blocking =
        GeneralRules.NO_BLOCK_IN_REACTIVE_CODE.evaluate(reactive).getFailureReport().toString();
    assertTrue(blocking.contains("Mono.block()"), blocking);
    assertTrue(blocking.contains("Flux.blockLast()"), blocking);
    String hooks =
        GeneralRules.NO_REACTOR_GLOBAL_HOOKS.evaluate(reactive).getFailureReport().toString();
    assertTrue(hooks.contains("Hooks.onOperatorDebug()"), hooks);

    JavaClasses clean =
        new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.reactiveclean");
    assertFalse(GeneralRules.NO_BLOCK_IN_REACTIVE_CODE.evaluate(clean).hasViolation());
    assertFalse(GeneralRules.NO_REACTOR_GLOBAL_HOOKS.evaluate(clean).hasViolation());
  }

  @Test
  void verifyReportsEveryViolatedRuleAtOnce() {
    ClassFileImporter importer = new ClassFileImporter();
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                AxizArchitecture.verify(
                    importer.importPackages("pe.axiz.quality.archunit.sample.reactive"),
                    "pe.axiz.quality.archunit.sample.reactive",
                    AxizArchitecture.Style.CQRS));
    assertTrue(e.getMessage().contains("Mono/Flux block"), e.getMessage());
    assertTrue(e.getMessage().contains("Reactor Hooks"), e.getMessage());
    AxizArchitecture.verify(
        importer.importPackages("pe.axiz.quality.archunit.sample.reactiveclean"),
        "pe.axiz.quality.archunit.sample.reactiveclean",
        AxizArchitecture.Style.CQRS);
  }

  @Test
  void beanNamesFollowSpring() {
    JavaClasses beans =
        new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.beans");
    assertEquals(
        "paymentsSupport",
        BeanNames.defaultName(beans.get("pe.axiz.quality.archunit.sample.beans.PaymentsSupport")));
    ClassFileImporter importer = new ClassFileImporter();
    // two capitals keep the name as is; nested classes keep the outer name
    assertEquals(
        "URLDecoder", BeanNames.defaultName(importer.importClass(java.net.URLDecoder.class)));
    assertEquals(
        "map.Entry", BeanNames.defaultName(importer.importClass(java.util.Map.Entry.class)));
  }
}
