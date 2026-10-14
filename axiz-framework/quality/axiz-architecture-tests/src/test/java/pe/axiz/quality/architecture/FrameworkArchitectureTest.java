package pe.axiz.quality.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Dependency rules of the framework itself (section 3 of the definition), checked on every build
 * over the whole reactor.
 */
@AnalyzeClasses(packages = "pe.axiz", importOptions = ImportOption.DoNotIncludeTests.class)
class FrameworkArchitectureTest {

  /** Layer 1 is plain Java: no Spring, no Quarkus, no upper layers. */
  @ArchTest
  static final ArchRule coreIsPlainJava =
      noClasses()
          .that()
          .resideInAPackage("pe.axiz.core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..", "io.quarkus..", "pe.axiz.spring..", "pe.axiz.framework..")
          .because("layer 1 is framework-neutral so a Quarkus extension can wire the same core");

  /** Layer 2 never depends on layer 3. */
  @ArchTest
  static final ArchRule springDoesNotKnowFrameworks =
      noClasses()
          .that()
          .resideInAPackage("pe.axiz.spring..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("pe.axiz.framework..");

  /** Framework contracts (-api) are plain Java over layer 1. */
  @ArchTest
  static final ArchRule frameworkApisArePlainJava =
      noClasses()
          .that(apiPackages())
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "pe.axiz.spring..", "reactor..")
          .because("the -api module of a framework depends only on layer 1");

  /**
   * A capability starter only uses its own package and the shared foundation adapters; capabilities
   * talk to each other through core ports.
   */
  @ArchTest
  static final ArchRule startersAreIsolated =
      ArchRuleDefinition.classes()
          .that()
          .resideInAPackage("pe.axiz.spring..")
          .should(onlyUseOwnCapabilityOrFoundation());

  /** No cycles between core capabilities. */
  @ArchTest
  static final ArchRule coreHasNoCycles =
      slices().matching("pe.axiz.core.(*)..").should().beFreeOfCycles();

  /** No cycles between framework styles. */
  @ArchTest
  static final ArchRule frameworksHaveNoCycles =
      slices().matching("pe.axiz.framework.(*)..").should().beFreeOfCycles();

  static DescribedPredicate<JavaClass> apiPackages() {
    return DescribedPredicate.describe(
        "framework api classes",
        c ->
            c.getPackageName().startsWith("pe.axiz.framework.")
                && !c.getPackageName().contains(".spring"));
  }

  static String capability(String packageName) {
    String rest = packageName.substring("pe.axiz.spring.".length());
    int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }

  static ArchCondition<JavaClass> onlyUseOwnCapabilityOrFoundation() {
    return new ArchCondition<>("only depend on their own capability or the foundation starter") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        String own = capability(item.getPackageName());
        item.getDirectDependenciesFromSelf()
            .forEach(
                d -> {
                  String target = d.getTargetClass().getPackageName();
                  if (target.startsWith("pe.axiz.spring.")) {
                    String other = capability(target);
                    if (!other.equals(own) && !other.equals("foundation")) {
                      events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                    }
                  }
                });
      }
    };
  }
}
