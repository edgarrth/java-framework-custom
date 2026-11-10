package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Role boundaries that apply in addition to the chosen package structure. */
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
      rules.add(
          noClasses()
              .should()
              .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
              .because("event processors and batch have no public REST API"));
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
