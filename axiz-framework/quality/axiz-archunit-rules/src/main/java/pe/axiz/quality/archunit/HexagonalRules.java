package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            .whereLayer("Config")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Adapters")
            .mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapters", "Config"),
        classes().that().resideInAnyPackage(domain, application).should(noHiddenInfrastructure()),
        classes()
            .that()
            .resideInAPackage(domain)
            .should()
            .onlyDependOnClassesThat(
                JavaClass.Predicates.resideInAnyPackage(
                        "java..",
                        "org.jspecify.annotations..",
                        domain,
                        "pe.axiz.core.domain..",
                        "pe.axiz.core.money..",
                        "pe.axiz.core.errors..",
                        "pe.axiz.core.id..",
                        "pe.axiz.core.identifiers..",
                        "pe.axiz.core.sensitive..",
                        "pe.axiz.core.time..")
                    .or(neutralLanguageTypes())),
        noClasses()
            .that()
            .resideInAPackage(application)
            .should()
            .dependOnClassesThat(
                new DescribedPredicate<JavaClass>("transport response or header contracts") {
                  @Override
                  public boolean test(JavaClass type) {
                    String name = type.getBaseComponentType().getName();
                    return Set.of(
                            "ApiResponse",
                            "PageResponse",
                            "HeaderCatalog",
                            "HeaderDefinition",
                            "StandardHeaders")
                        .stream()
                        .map(n -> "pe.axiz.core.commons." + n)
                        .anyMatch(n -> name.equals(n) || name.startsWith(n + "$"));
                  }
                }),
        classes()
            .that()
            .resideInAPackage(application)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "java..",
                "org.jspecify.annotations..",
                "org.slf4j..",
                domain,
                application,
                "pe.axiz.core..",
                "pe.axiz.structure.hexagonal..",
                "pe.axiz.extension.cqrs..",
                "reactor.core.publisher..",
                "org.reactivestreams.."),
        noClasses()
            .that()
            .resideInAPackage(application)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "jakarta.servlet..",
                "jakarta.ws.rs..",
                "software.amazon..",
                "io.lettuce..",
                "org.apache.kafka..",
                "tools.jackson..",
                "com.fasterxml.jackson..",
                "java.net..",
                "java.sql..",
                "javax.sql..",
                "io.r2dbc..",
                basePackage + ".api..",
                basePackage + ".client..",
                "pe.axiz.spring..",
                "pe.axiz.structure.hexagonal.spring..",
                "pe.axiz.extension.cqrs.spring..",
                "pe.axiz.adapter..",
                "pe.axiz.kit..")
            .because("application code uses ports instead of infrastructure or transport models"),
        noClasses()
            .that()
            .resideInAPackage(basePackage + ".adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(basePackage + ".adapter.out.."),
        noClasses()
            .that()
            .resideInAPackage(basePackage + ".adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(basePackage + ".adapter.in.."),
        classes()
            .that()
            .resideInAPackage(basePackage + ".application.port..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "java..",
                "org.jspecify.annotations..",
                domain,
                application,
                "pe.axiz.structure.hexagonal",
                "pe.axiz.structure.hexagonal.annotation..",
                "pe.axiz.extension.cqrs",
                "pe.axiz.core.commons..",
                "pe.axiz.core.lang..",
                "pe.axiz.core.money..",
                "pe.axiz.core.errors..",
                "pe.axiz.core.domain..",
                "pe.axiz.core.id..",
                "pe.axiz.core.sensitive..",
                "reactor.core.publisher..",
                "org.reactivestreams.."),
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

  private static DescribedPredicate<JavaClass> neutralLanguageTypes() {
    return new DescribedPredicate<>("pure language value helpers") {
      @Override
      public boolean test(JavaClass type) {
        return Set.of("Checks", "Strings", "Dates", "Result", "Result$Ok", "Result$Err").stream()
            .anyMatch(
                name -> type.getBaseComponentType().getName().equals("pe.axiz.core.lang." + name));
      }
    };
  }

  private static ArchCondition<JavaClass> noHiddenInfrastructure() {
    return new ArchCondition<>("not acquire infrastructure through Axiz contracts") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        var queue = new ArrayDeque<JavaClass>();
        var seen = new HashSet<String>();
        queue.add(item);
        while (!queue.isEmpty()) {
          var current = queue.remove();
          if (!seen.add(current.getName())) continue;
          for (var dependency : current.getDirectDependenciesFromSelf()) {
            var target = dependency.getTargetClass();
            String name = target.getName();
            if (name.startsWith("pe.axiz.adapter.")
                || name.startsWith("pe.axiz.kit.")
                || name.startsWith("pe.axiz.spring.")
                || name.startsWith("org.springframework.")
                || name.startsWith("software.amazon.")
                || name.startsWith("jakarta.persistence.")
                || name.startsWith("io.lettuce.")
                || name.startsWith("org.apache.kafka.")
                || name.startsWith("io.r2dbc.")
                || name.startsWith("tools.jackson.")
                || name.startsWith("com.fasterxml.jackson.")
                || name.startsWith("java.net.http.HttpClient")
                || name.startsWith("java.net.http.HttpRequest")
                || name.startsWith("java.net.http.HttpResponse")
                || name.startsWith("java.sql.")
                || name.startsWith("javax.sql.")) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getName()
                          + " reaches infrastructure through "
                          + dependency.getDescription()));
            } else if (!target.isPrimitive()
                && !target.isArray()
                && !name.startsWith("pe.axiz.")
                && !name.startsWith("java.")
                && !name.startsWith("javax.")
                && !name.startsWith("org.jspecify.")
                && !name.startsWith("org.slf4j.")
                && !name.startsWith("io.micrometer.")
                && !name.startsWith("reactor.")
                && !name.startsWith("org.reactivestreams.")) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getName()
                          + " reaches an unapproved library through "
                          + dependency.getDescription()));
            } else if (name.startsWith("pe.axiz.core.")) {
              queue.add(target);
            }
          }
        }
      }
    };
  }
}
