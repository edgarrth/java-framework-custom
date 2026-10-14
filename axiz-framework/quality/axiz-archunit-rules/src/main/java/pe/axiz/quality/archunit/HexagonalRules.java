package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * Hexagonal style: {@code domain} at the center, {@code application} (use cases and ports) around
 * it, {@code adapter.in} and {@code adapter.out} outside, {@code config} wiring everything.
 * Dependencies point inwards only.
 */
public final class HexagonalRules {

  private HexagonalRules() {}

  public static List<ArchRule> rules(String basePackage) {
    String domain = basePackage + ".domain..";
    String application = basePackage + ".application..";
    String adapters = basePackage + ".adapter..";
    String config = basePackage + ".config..";
    return List.of(
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain")
            .definedBy(domain)
            .layer("Application")
            .definedBy(application)
            .layer("Adapters")
            .definedBy(adapters)
            .layer("Config")
            .definedBy(config)
            .whereLayer("Adapters")
            .mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapters", "Config"),
        noClasses()
            .that()
            .resideInAPackage(domain)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "software.amazon..",
                "tools.jackson..",
                "com.fasterxml.jackson.databind..")
            .because("the domain is plain Java"),
        noClasses()
            .that()
            .resideInAPackage(application)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.web..", "jakarta.persistence..", "software.amazon..")
            .because("use cases talk to the outside through ports"),
        classes()
            .that()
            .haveSimpleNameEndingWith("UseCase")
            .and()
            .areNotInterfaces()
            .should()
            .resideInAPackage(application),
        classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .resideInAPackage(basePackage + ".adapter.in.."),
        classes().that().haveSimpleNameEndingWith("Adapter").should().resideInAPackage(adapters));
  }
}
