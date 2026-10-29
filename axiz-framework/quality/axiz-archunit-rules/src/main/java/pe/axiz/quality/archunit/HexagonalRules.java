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
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "java..",
                "org.jspecify.annotations..",
                domain,
                "pe.axiz.core.commons..",
                "pe.axiz.core.domain..",
                "pe.axiz.core.money..",
                "pe.axiz.core.errors..",
                "pe.axiz.core.id..",
                "pe.axiz.core.identifiers..",
                "pe.axiz.core.sensitive..",
                "pe.axiz.core.time.."),
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
                "pe.axiz.framework.hexagonal..",
                "pe.axiz.framework.cqrs..",
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
                "pe.axiz.framework.hexagonal.spring..",
                "pe.axiz.framework.cqrs.spring..",
                "pe.axiz.core.persistence..",
                "pe.axiz.core.messaging.kafka..",
                "pe.axiz.core.messaging.sqs..",
                "pe.axiz.core.messaging.sns..",
                "pe.axiz.core.cache.redis..",
                "pe.axiz.core.crypto.kms..",
                "pe.axiz.core.crypto.jose..",
                "pe.axiz.core.storage.s3..",
                "pe.axiz.core.secrets.aws..",
                "pe.axiz.core.idempotency.redis..",
                "pe.axiz.core.idempotency.dynamodb..",
                "pe.axiz.core.lock.redis..",
                "pe.axiz.core.lock.dynamodb..",
                "pe.axiz.core.inbox.r2dbc..",
                "pe.axiz.core.inbox.dynamodb..",
                "pe.axiz.core.outbox.r2dbc..")
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
                "pe.axiz.framework.hexagonal",
                "pe.axiz.framework.hexagonal.annotation..",
                "pe.axiz.framework.cqrs",
                "pe.axiz.core.commons..",
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

  private static com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaClass>
      noHiddenInfrastructure() {
    return new com.tngtech.archunit.lang.ArchCondition<>(
        "not acquire infrastructure through Axiz contracts") {
      @Override
      public void check(
          com.tngtech.archunit.core.domain.JavaClass item,
          com.tngtech.archunit.lang.ConditionEvents events) {
        var queue = new java.util.ArrayDeque<com.tngtech.archunit.core.domain.JavaClass>();
        var seen = new java.util.HashSet<String>();
        queue.add(item);
        while (!queue.isEmpty()) {
          var current = queue.remove();
          if (!seen.add(current.getName())) continue;
          for (var dependency : current.getDirectDependenciesFromSelf()) {
            var target = dependency.getTargetClass();
            String name = target.getName();
            if (name.startsWith("pe.axiz.spring.")
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
                  com.tngtech.archunit.lang.SimpleConditionEvent.violated(
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
                  com.tngtech.archunit.lang.SimpleConditionEvent.violated(
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
