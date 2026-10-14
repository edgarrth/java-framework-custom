package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** Event-driven style: handlers live in {@code handler}, never expose HTTP controllers. */
public final class EdaRules {

  private EdaRules() {}

  public static List<ArchRule> rules(String basePackage) {
    return List.of(
        classes()
            .that()
            .implement("pe.axiz.core.messaging.EventHandler")
            .should()
            .resideInAPackage(basePackage + ".handler.."),
        noClasses()
            .that()
            .resideInAPackage(basePackage + "..")
            .should()
            .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .because("event processors expose no public API"));
  }
}
