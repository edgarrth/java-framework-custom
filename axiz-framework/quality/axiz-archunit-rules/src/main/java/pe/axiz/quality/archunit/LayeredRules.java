package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * Estilo layered: {@code controller -> service -> repository}, con las entidades de persistencia en
 * los repositorios.
 */
public final class LayeredRules {

  private LayeredRules() {}

  public static List<ArchRule> rules(String basePackage) {
    return List.of(
        PackageClosure.rule(
            basePackage, List.of("controller", "service", "repository", "model", "config")),
        PackageClosure.bootstrapOnlyStarts(basePackage),
        classes()
            .that()
            .haveSimpleNameEndingWith("Service")
            .should()
            .resideInAPackage(basePackage + ".service.."),
        noClasses()
            .that()
            .resideInAPackage(basePackage + ".service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.web..", "org.springframework.http..", "jakarta.servlet.."),
        noClasses()
            .that()
            .resideInAPackage(basePackage + ".controller..")
            .should()
            .dependOnClassesThat(
                DescribedPredicate.describe(
                    "mapped persistent entities",
                    type ->
                        type.isAnnotatedWith("jakarta.persistence.Entity")
                            || type.isAnnotatedWith(
                                "software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean"))),
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
