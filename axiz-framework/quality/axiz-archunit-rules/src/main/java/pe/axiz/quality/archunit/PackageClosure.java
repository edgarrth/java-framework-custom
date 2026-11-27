package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import java.util.Collection;
import java.util.List;

/**
 * Propiedad cerrada de paquetes: agregar un paquete no puede eludir en silencio las reglas de
 * dependencias.
 */
final class PackageClosure {
  static final String BOOT = "org.springframework.boot.autoconfigure.SpringBootApplication";

  private PackageClosure() {}

  static ArchRule rule(String base, Collection<String> allowed) {
    String[] packages = allowed.stream().map(p -> base + "." + p + "..").toArray(String[]::new);
    var governed =
        JavaClass.Predicates.resideInAnyPackage(packages)
            .or(
                DescribedPredicate.describe(
                    "root bootstrap or package metadata",
                    c ->
                        c.getPackageName().equals(base)
                            && (bootstrap(c) || c.getSimpleName().equals("package-info"))));
    return classes()
        .that()
        .resideInAPackage(base + "..")
        .should(ArchConditions.be(governed))
        .because("every production class belongs to a governed package");
  }

  static boolean bootstrap(JavaClass type) {
    return type.isAnnotatedWith(BOOT) || type.isMetaAnnotatedWith(BOOT);
  }

  static ArchRule bootstrapOnlyStarts(String base) {
    return classes()
        .that(DescribedPredicate.describe("application bootstrap", PackageClosure::bootstrap))
        .should(
            new ArchCondition<>("only start the application at its base package") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                boolean valid =
                    type.getPackageName().equals(base)
                        && type.getFields().isEmpty()
                        && type.getMethods().size() == 1
                        && type.getMethods().stream()
                            .allMatch(
                                m ->
                                    m.getName().equals("main")
                                        && m.getModifiers().contains(JavaModifier.STATIC)
                                        && m.getModifiers().contains(JavaModifier.PUBLIC)
                                        && m.getRawReturnType().isEquivalentTo(void.class)
                                        && m.getRawParameterTypes().size() == 1
                                        && m.getRawParameterTypes()
                                            .getFirst()
                                            .isEquivalentTo(String[].class));
                events.add(
                    new SimpleConditionEvent(
                        type,
                        valid,
                        type.getName()
                            + " must contain only public static void main(String[]) at "
                            + base));
              }
            });
  }

  static List<ArchRule> noHttpApi() {
    return List.of(
        noMethods()
            .should()
            .beMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping"),
        noClasses().should().beMetaAnnotatedWith("org.springframework.stereotype.Controller"),
        noMethods()
            .should()
            .haveRawReturnType(
                JavaClass.Predicates.assignableTo(
                        "org.springframework.web.servlet.function.RouterFunction")
                    .or(
                        JavaClass.Predicates.assignableTo(
                            "org.springframework.web.reactive.function.server.RouterFunction"))));
  }
}
