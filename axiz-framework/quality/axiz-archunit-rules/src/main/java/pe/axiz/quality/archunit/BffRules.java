package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** BFF style: no own persistence and no domain model; it composes downstream APIs. */
public final class BffRules {

  private BffRules() {}

  public static List<ArchRule> rules(String basePackage) {
    return List.of(
        noClasses()
            .that()
            .resideInAPackage(basePackage + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "jakarta.persistence..",
                "org.springframework.data.jpa..",
                "software.amazon.awssdk.enhanced.dynamodb..")
            .because("a BFF has no persistence of its own"));
  }
}
