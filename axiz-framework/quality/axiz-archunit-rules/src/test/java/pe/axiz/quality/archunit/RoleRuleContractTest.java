package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.kit.jpa.BaseEntity;
import pe.axiz.quality.archunit.sample.guarded.handler.BareRoute;
import pe.axiz.quality.archunit.sample.guarded.model.Amount;
import pe.axiz.quality.archunit.sample.guarded.rogue.Rogue;
import pe.axiz.quality.archunit.sample.guarded.service.Singletons;
import pe.axiz.quality.archunit.sample.reactive.BlockingOperations;

class RoleRuleContractTest {
  private static final String BASE = "pe.axiz.quality.archunit.sample.guarded";

  private static boolean violates(List<ArchRule> rules, JavaClasses classes) {
    return rules.stream()
        .anyMatch(rule -> rule.allowEmptyShould(true).evaluate(classes).hasViolation());
  }

  @Test
  void eachStructureRejectsProductionCodeOutsideItsClosedPackageVocabulary() {
    var rogue = new ClassFileImporter().importClasses(Rogue.class);
    for (var rules :
        List.of(
            HexagonalRules.rules(BASE),
            LayeredRules.rules(BASE),
            BffRules.rules(BASE),
            EdaRules.rules(BASE),
            BatchRules.rules(BASE),
            CqrsRules.rules(BASE))) assertTrue(violates(rules, rogue));
  }

  @Test
  void archetypeRulesIndependentlyEnforceNoHttpAndNoDomainMessaging() {
    var http = new ClassFileImporter().importClasses(BareRoute.class);
    for (var role : List.of(Archetype.EVENT_PROCESSOR, Archetype.BATCH))
      assertTrue(violates(ArchetypeRules.rules(role), http));
    assertFalse(violates(ArchetypeRules.rules(Archetype.BUSINESS), http));
    for (var role : List.of(Archetype.UX, Archetype.CORE, Archetype.GATEWAY)) {
      var rules = ArchetypeRules.rules(role);
      var persistence = new ClassFileImporter().importClasses(OwnedPersistence.class);
      assertTrue(violates(rules, persistence));
      assertTrue(violates(rules, new ClassFileImporter().importClasses(OwnedMessaging.class)));
    }
  }

  static class OwnedPersistence {
    BaseEntity entity;
  }

  static class OwnedMessaging {
    EventPublisher publisher;
  }

  @Test
  void singletonDetectionHandlesBothDirectAndComposedStereotypesWithoutMatchingOrdinaryRecords() {
    var importer = new ClassFileImporter();
    var service = importer.importClass(Singletons.Unreviewed.class);
    var plain = importer.importClass(Amount.class);
    assertTrue(
        GeneralRules.annotatedWithAny(List.of("org.springframework.stereotype.Service"))
            .test(service));
    assertTrue(
        GeneralRules.annotatedWithAny(List.of("org.springframework.stereotype.Component"))
            .test(service));
    assertFalse(
        GeneralRules.annotatedWithAny(List.of("org.springframework.stereotype.Component"))
            .test(plain));
    assertFalse(GeneralRules.annotatedWithAny(List.of()).test(service));
  }

  @Test
  void reactiveCompositionDetectsEveryWaitAndTheServletSelectorDoesNotAddThoseRules() {
    var classes = new ClassFileImporter().importClasses(BlockingOperations.class);
    String report =
        ReactiveRules.rules().stream()
            .map(
                rule -> rule.allowEmptyShould(true).evaluate(classes).getFailureReport().toString())
            .collect(Collectors.joining("\n"));
    for (String call :
        List.of(
            "Future.get()",
            "CompletableFuture.get()",
            "Flux.blockFirst()",
            "Flux.toIterable()",
            "Schedulers.newBoundedElastic",
            "Thread.sleep",
            "HttpClient.send")) assertTrue(report.contains(call), report);
    assertEquals(Stack.REACTIVE, Stack.parse(" reactive "));
    assertEquals(Structure.EDA, Structure.parse(" eda "));
    assertEquals(Archetype.EVENT_PROCESSOR, Archetype.parse("event-processor"));
    assertThrows(IllegalArgumentException.class, () -> Structure.parse("cqrs"));
    var architecture =
        AxizArchitecture.of("pe.axiz.sample")
            .archetype(Archetype.BUSINESS)
            .structure(Structure.HEXAGONAL);
    long servlet =
        architecture.stack(Stack.SERVLET).rules().stream()
            .filter(rule -> rule.getDescription().contains("scheduler offload"))
            .count();
    long reactive =
        architecture.stack(Stack.REACTIVE).rules().stream()
            .filter(rule -> rule.getDescription().contains("scheduler offload"))
            .count();
    assertEquals(0, servlet);
    assertEquals(1, reactive);
  }
}
