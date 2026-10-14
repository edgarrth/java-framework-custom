package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** Layered style: {@code controller -> service -> repository}, entities in {@code model}. */
public final class LayeredRules {

  private LayeredRules() {}

  public static List<ArchRule> rules(String basePackage) {
    return List.of(
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller")
            .definedBy(basePackage + ".controller..")
            .layer("Service")
            .definedBy(basePackage + ".service..")
            .layer("Repository")
            .definedBy(basePackage + ".repository..")
            .layer("Model")
            .definedBy(basePackage + ".model..")
            .whereLayer("Controller")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service"),
        classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .resideInAPackage(basePackage + ".controller.."),
        classes()
            .that()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .resideInAPackage(basePackage + ".repository.."));
  }
}
