package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Límites de rol que se aplican además de la estructura de paquetes elegida. */
public final class ArchetypeRules {
  private ArchetypeRules() {}

  public static List<ArchRule> rules(Archetype archetype) {
    ArrayList<ArchRule> rules = new ArrayList<>();
    if (Set.of(Archetype.UX, Archetype.CORE, Archetype.GATEWAY).contains(archetype)) {
      rules.add(
          noClasses()
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "jakarta.persistence..",
                  "io.r2dbc..",
                  "software.amazon.awssdk.enhanced.dynamodb..",
                  "pe.axiz.kit..")
              .because("this role owns no domain persistence"));
    }
    if (archetype == Archetype.EVENT_PROCESSOR || archetype == Archetype.BATCH) {
      rules.addAll(PackageClosure.noHttpApi());
    }
    if (Set.of(Archetype.UX, Archetype.CORE, Archetype.GATEWAY).contains(archetype)) {
      rules.add(
          noClasses()
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "pe.axiz.core.messaging..",
                  "pe.axiz.spring.messaging..",
                  "pe.axiz.spring.outbox..")
              .because("this role does not participate in domain messaging"));
    }
    return List.copyOf(rules);
  }
}
