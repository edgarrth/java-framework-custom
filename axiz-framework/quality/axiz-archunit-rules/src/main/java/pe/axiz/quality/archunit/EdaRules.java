package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;

/** Event-driven style: handlers live in {@code handler}, never expose HTTP controllers. */
public final class EdaRules {

  private EdaRules() {}

  public static List<ArchRule> rules(String basePackage) {
    var rules =
        new ArrayList<ArchRule>(
            List.of(
                PackageClosure.rule(
                    basePackage, List.of("handler", "event", "repository", "config")),
                PackageClosure.bootstrapOnlyStarts(basePackage),
                noClasses()
                    .that()
                    .resideInAPackage(basePackage + ".handler..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("jakarta.persistence..", "java.sql..", "pe.axiz.kit.."),
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
                    .because("event processors expose no public API")));
    rules.addAll(PackageClosure.noHttpApi());
    return List.copyOf(rules);
  }
}
