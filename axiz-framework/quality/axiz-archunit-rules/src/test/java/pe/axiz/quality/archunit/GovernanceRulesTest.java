package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.sample.badstart.Bad;
import pe.axiz.quality.archunit.sample.guarded.FakeApplication;
import pe.axiz.quality.archunit.sample.guarded.controller.TransactionalController;
import pe.axiz.quality.archunit.sample.guarded.domain.Ambient;
import pe.axiz.quality.archunit.sample.guarded.handler.BareRoute;
import pe.axiz.quality.archunit.sample.guarded.handler.HttpHandler;
import pe.axiz.quality.archunit.sample.guarded.model.Amount;
import pe.axiz.quality.archunit.sample.guarded.repository.archive.Archive;
import pe.axiz.quality.archunit.sample.guarded.rogue.Rogue;
import pe.axiz.quality.archunit.sample.guarded.service.Singletons;
import pe.axiz.quality.archunit.sample.start.Good;

class GovernanceRulesTest {
  @Test
  void selfCheckUsesTheActualRuleSetAndRejectsAnEmptyOne() {
    ArchitectureSelfCheck.verify(AxizArchitecture.of("pe.axiz.consumer"));
    assertThrows(AssertionError.class, () -> ArchitectureSelfCheck.verify(List.of()));
  }

  private static JavaClasses types(Class<?>... types) {
    return new ClassFileImporter().importClasses(types);
  }

  private static boolean fails(ArchRule rule, Class<?>... types) {
    return rule.allowEmptyShould(true).evaluate(types(types)).hasViolation();
  }

  @Test
  void packageNamesAndFakeBootstrapsCannotEscapeOwnership() {
    var rule =
        PackageClosure.rule(
            "pe.axiz.quality.archunit.sample.guarded", List.of("model", "repository"));
    assertTrue(fails(rule, Rogue.class));
    assertTrue(fails(rule, FakeApplication.class));
    assertFalse(fails(rule, Archive.class, Amount.class));
    assertFalse(
        fails(
            PackageClosure.rule("pe.axiz.quality.archunit.sample.start", List.of("domain")),
            Good.class));
    assertFalse(
        fails(
            PackageClosure.bootstrapOnlyStarts("pe.axiz.quality.archunit.sample.start"),
            Good.class));
    assertTrue(
        fails(
            PackageClosure.bootstrapOnlyStarts("pe.axiz.quality.archunit.sample.badstart"),
            Bad.class));
    assertTrue(
        fails(
            PackageClosure.bootstrapOnlyStarts("pe.axiz.quality.archunit.sample.other"),
            Good.class));
  }

  @Test
  void directAndComposedHttpMappingsAreRejectedForEventAndBatchRoles() {
    for (Class<?> type : List.of(HttpHandler.class, BareRoute.class))
      assertTrue(PackageClosure.noHttpApi().stream().anyMatch(rule -> fails(rule, type)));
    assertTrue(
        EdaRules.rules("pe.axiz.quality.archunit.sample.guarded").stream()
            .anyMatch(rule -> fails(rule, HttpHandler.class)));
    assertTrue(
        BatchRules.rules("pe.axiz.quality.archunit.sample.guarded").stream()
            .anyMatch(rule -> fails(rule, BareRoute.class)));
  }

  @Test
  void businessUsesExactNumbersInjectedTimeAndNoAmbientEnvironment() {
    assertTrue(fails(GeneralRules.NO_FLOATING_POINT_IN_BUSINESS_MODELS, Ambient.class));
    assertTrue(fails(GeneralRules.NO_DOUBLE_DECIMAL_CONSTRUCTION, Ambient.class));
    assertTrue(fails(GeneralRules.BUSINESS_USES_INJECTED_CLOCK, Ambient.class));
    assertTrue(
        HexagonalRules.rules("pe.axiz.quality.archunit.sample.guarded").stream()
            .anyMatch(rule -> fails(rule, Ambient.class)));
    for (var rule :
        List.of(
            GeneralRules.NO_FLOATING_POINT_IN_BUSINESS_MODELS,
            GeneralRules.NO_DOUBLE_DECIMAL_CONSTRUCTION,
            GeneralRules.BUSINESS_USES_INJECTED_CLOCK)) assertFalse(fails(rule, Amount.class));
    assertTrue(
        fails(
            GeneralRules.NO_TRANSACTIONAL_TRANSPORT_OR_DOMAIN_METHODS,
            TransactionalController.class));
  }

  @Test
  void reviewIsRequiredForInjectedCollectionsAtomicsAndConcreteBeanReturnTypes() {
    var rule = GeneralRules.SINGLETON_FIELDS_OF_MUTABLE_TYPE_ARE_REVIEWED;
    assertTrue(fails(rule, Singletons.Unreviewed.class));
    assertFalse(fails(rule, Singletons.Reviewed.class));
    assertTrue(fails(rule, Singletons.Empty.class));
    assertTrue(fails(rule, Singletons.FactoryBean.class, Singletons.class));
  }
}
