package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** Estilo BFF: sin persistencia propia ni modelo de dominio; compone API aguas abajo. */
public final class BffRules {

  private BffRules() {}

  public static List<ArchRule> rules(String basePackage) {
    return List.of(
        PackageClosure.rule(basePackage, List.of("controller", "composition", "client", "config")),
        PackageClosure.bootstrapOnlyStarts(basePackage),
        noClasses()
            .that()
            .resideInAPackage(basePackage + ".client..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(basePackage + ".controller.."),
        noClasses()
            .that()
            .resideInAPackage(basePackage + ".controller..")
            .should()
            .dependOnClassesThat(
                DescribedPredicate.describe(
                    "other application layers",
                    type -> {
                      String pkg = type.getPackageName();
                      return pkg.startsWith(basePackage + ".")
                          && !pkg.equals(basePackage + ".controller")
                          && !pkg.startsWith(basePackage + ".controller.")
                          && !pkg.equals(basePackage + ".composition")
                          && !pkg.startsWith(basePackage + ".composition.")
                          && !pkg.equals(basePackage + ".client")
                          && !pkg.startsWith(basePackage + ".client.");
                    })),
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
