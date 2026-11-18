package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AxizArchitectureTest {

  private final JavaClasses sample =
      new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.hex");

  @Test
  void hexagonalRulesDetectOutwardDependencies() {
    ArchRule layering =
        HexagonalRules.rules("pe.axiz.quality.archunit.sample.hex").stream()
            .filter(rule -> rule.getDescription().contains("Layered architecture"))
            .findFirst()
            .orElseThrow();
    assertTrue(layering.evaluate(sample).hasViolation());
    String report = layering.evaluate(sample).getFailureReport().toString();
    assertTrue(report.contains("BadDomain"), report);
  }

  @Test
  void everyStyleHasRules() {
    for (Structure structure : Structure.values()) {
      Archetype role =
          switch (structure) {
            case EDA -> Archetype.EVENT_PROCESSOR;
            case BFF -> Archetype.UX;
            case BATCH -> Archetype.BATCH;
            default -> Archetype.BUSINESS;
          };
      assertFalse(
          AxizArchitecture.of("pe.axiz.x").archetype(role).structure(structure).rules().isEmpty());
    }
    assertEquals(Extension.CQRS, Extension.parse(" cqrs "));
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
                AxizArchitecture.of("pe.axiz.quality.archunit.sample.reactive")
                    .extensions(Extension.CQRS)
                    .verify(importer.importPackages("pe.axiz.quality.archunit.sample.reactive")));
    assertTrue(e.getMessage().contains("Mono/Flux block"), e.getMessage());
    assertTrue(e.getMessage().contains("Reactor Hooks"), e.getMessage());
    AxizArchitecture.of("pe.axiz.quality.archunit.sample.reactiveclean")
        .extensions(Extension.CQRS)
        .verify(importer.importPackages("pe.axiz.quality.archunit.sample.reactiveclean"));
  }

  @Test
  void reactiveStackRejectsOffloadAndFutureWaits() {
    JavaClasses bad =
        new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.reactive");
    String report =
        ReactiveRules.rules().stream()
            .map(rule -> rule.evaluate(bad).getFailureReport().toString())
            .collect(Collectors.joining("\n"));
    for (String violation :
        List.of(
            "Mono.block()",
            "Flux.blockLast()",
            "boundedElastic()",
            "CompletableFuture.join()",
            "SecretResolver",
            "DataKeyProvider",
            "EnvelopeEncryptor",
            "AuditPublisher",
            "AuditTrail",
            "AccessTokenProvider",
            "NotificationSender")) {
      assertTrue(report.contains(violation), report);
    }
    JavaClasses clean =
        new ClassFileImporter().importPackages("pe.axiz.quality.archunit.sample.reactiveclean");
    ReactiveRules.rules()
        .forEach(rule -> assertFalse(rule.evaluate(clean).hasViolation(), rule.getDescription()));
  }

  @ParameterizedTest
  @CsvSource({
    "BUSINESS,SERVLET,HEXAGONAL,true,true", "BUSINESS,REACTIVE,LAYERED,false,true",
    "SUPPORT,SERVLET,HEXAGONAL,false,true", "SUPPORT,REACTIVE,LAYERED,false,true",
    "CORE,REACTIVE,HEXAGONAL,false,true", "GATEWAY,SERVLET,HEXAGONAL,false,true",
    "UX,REACTIVE,BFF,false,true", "EVENT_PROCESSOR,REACTIVE,EDA,false,true",
    "EVENT_PROCESSOR,REACTIVE,HEXAGONAL,true,true", "BATCH,SERVLET,BATCH,false,true",
    "BATCH,REACTIVE,BATCH,false,false", "BUSINESS,SERVLET,EDA,false,false",
    "GATEWAY,SERVLET,LAYERED,false,false", "CORE,REACTIVE,HEXAGONAL,true,false",
    "UX,REACTIVE,BFF,true,false", "BUSINESS,SERVLET,LAYERED,true,false",
    "SUPPORT,SERVLET,HEXAGONAL,true,false", "UX,SERVLET,HEXAGONAL,false,false"
  })
  void serviceSelectorsEnforceTheAdrCatalog(
      Archetype role, Stack stack, Structure structure, boolean cqrs, boolean valid) {
    var architecture =
        AxizArchitecture.of("pe.axiz.example").archetype(role).stack(stack).structure(structure);
    if (cqrs) architecture.extensions(Extension.CQRS);
    if (valid) Assertions.assertDoesNotThrow(architecture::rules);
    else assertThrows(IllegalArgumentException.class, architecture::rules);
  }

  @Test
  void aWrongBasePackageCannotSilentlyPassWithNoProductionClasses() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AxizArchitecture.of("pe.axiz.nonexistent.service").verify());
    assertThrows(IllegalArgumentException.class, () -> AxizArchitecture.of(" "));
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
    assertEquals("URLDecoder", BeanNames.defaultName(importer.importClass(URLDecoder.class)));
    assertEquals("map.Entry", BeanNames.defaultName(importer.importClass(Entry.class)));
  }
}
