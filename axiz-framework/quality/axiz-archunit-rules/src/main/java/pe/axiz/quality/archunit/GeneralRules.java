package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.List;

/**
 * Hygiene rules every axiz service and module follows (section 16 of the definition). Annotation
 * types are referenced by name so the rules do not need Spring on the classpath.
 */
public final class GeneralRules {

  private static final List<String> SINGLETON_STEREOTYPES =
      List.of(
          "org.springframework.stereotype.Component",
          "org.springframework.stereotype.Service",
          "org.springframework.stereotype.Repository",
          "org.springframework.stereotype.Controller",
          "org.springframework.web.bind.annotation.RestController",
          "org.springframework.context.annotation.Configuration");

  /** Dependencies are injected through constructors only. */
  public static final ArchRule NO_FIELD_INJECTION =
      noFields()
          .should()
          .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
          .orShould()
          .beAnnotatedWith("jakarta.inject.Inject")
          .orShould()
          .beAnnotatedWith("org.springframework.beans.factory.annotation.Value")
          .because("constructor injection makes dependencies explicit and beans immutable");

  /** Singleton beans keep no mutable state between requests. */
  public static final ArchRule NO_MUTABLE_STATE_IN_SINGLETONS =
      fields()
          .that()
          .areDeclaredInClassesThat(annotatedWithAny(SINGLETON_STEREOTYPES))
          .and()
          .areNotStatic()
          .should()
          .beFinal()
          .because("request data must never be stored in singleton fields");

  public static final ArchRule NO_SYSTEM_OUT =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  public static final ArchRule NO_GENERIC_EXCEPTIONS =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  public static final ArchRule NO_JAVA_UTIL_LOGGING =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  public static final ArchRule NO_JODA_TIME = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

  /** No global Reactor hooks; context propagation is done by Micrometer. */
  public static final ArchRule NO_REACTOR_GLOBAL_HOOKS =
      noClasses()
          .should()
          .callMethodWhere(
              DescribedPredicate.describe(
                  "Reactor Hooks",
                  call -> call.getTargetOwner().getName().equals("reactor.core.publisher.Hooks")))
          .because("global hooks leak context and slow down every operator");

  /** Blocking calls are forbidden in reactive pipelines. */
  public static final ArchRule NO_BLOCK_IN_REACTIVE_CODE =
      noClasses()
          .should()
          .callMethodWhere(
              DescribedPredicate.describe(
                  "Mono/Flux block",
                  call -> {
                    String owner = call.getTargetOwner().getName();
                    return (owner.equals("reactor.core.publisher.Mono")
                            || owner.equals("reactor.core.publisher.Flux"))
                        && call.getName().startsWith("block");
                  }))
          .because("blocking a reactive pipeline starves the event loop");

  /** Packages of business operations in every style (use cases, handlers, services). */
  private static final String[] BUSINESS_PACKAGES = {
    "..application..", "..usecase..", "..service..", "..handler..", "..job.."
  };

  /**
   * Business operations never serialize with JVM-local locks: a service runs several replicas, so a
   * {@code ReentrantLock} or a {@code synchronized} method protects one pod only and two replicas
   * can still move the same account at once. They use the distributed lock ({@code LockTemplate} of
   * axiz-lock) or database constraints.
   */
  public static final ArchRule NO_JVM_LOCKS_IN_BUSINESS_CODE =
      noClasses()
          .that()
          .resideInAnyPackage(BUSINESS_PACKAGES)
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.util.concurrent.locks..")
          .because(
              "a lock in one JVM does not serialize the replicas of a service; use LockTemplate");

  /** Same rule for {@code synchronized} methods. */
  public static final ArchRule NO_SYNCHRONIZED_BUSINESS_METHODS =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(BUSINESS_PACKAGES)
          .should()
          .notHaveModifier(JavaModifier.SYNCHRONIZED)
          .because(
              "a lock in one JVM does not serialize the replicas of a service; use LockTemplate");

  /** Testing authentication tokens never reach production code. */
  public static final ArchRule NO_TESTING_AUTHENTICATION =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(
              "org.springframework.security.authentication.TestingAuthenticationToken");

  /** Parallel HTTP clients are not allowed; use @HttpExchange interfaces. */
  public static final ArchRule NO_PARALLEL_HTTP_CLIENTS =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("retrofit2..", "feign..", "okhttp3..")
          .because("axiz standardizes on Spring HTTP interface clients");

  /** AWS SDK v1 is forbidden. */
  public static final ArchRule NO_AWS_SDK_V1 =
      noClasses().should().dependOnClassesThat().resideInAPackage("com.amazonaws..");

  /** Services consume ports and public starter facades; adapters are wired at runtime. */
  public static final ArchRule NO_TECHNOLOGY_ADAPTERS =
      noClasses().should().dependOnClassesThat().resideInAPackage("pe.axiz.adapter..");

  /** Auto-configuration classes belong to the framework's wiring, not service dependencies. */
  public static final ArchRule NO_AUTOCONFIGURATION_DEPENDENCIES =
      noClasses().should().dependOnClassesThat().haveSimpleNameEndingWith("AutoConfiguration");

  /** Technology kits are restricted to outbound persistence adapters and repositories. */
  public static final ArchRule KITS_ONLY_IN_PERSISTENCE_PACKAGES =
      noClasses()
          .that()
          .resideOutsideOfPackages("..adapter.out.persistence..", "..repository..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("pe.axiz.kit..");

  /**
   * A new framework starts without deprecated code: nothing may call, extend or annotate with an
   * API its owner marked {@code @Deprecated} (Spring, AWS SDK, Jackson, axiz itself).
   */
  public static final ArchRule NO_DEPRECATED_API =
      GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED;

  /**
   * Every scanned component has a bean name of its own. A {@code @Configuration} class named like
   * one of its {@code @Bean} methods (class {@code SettlementJob} with {@code Job settlementJob()})
   * compiles and passes unit tests, and the application does not start.
   */
  public static final ArchRule UNIQUE_BEAN_NAMES =
      uniqueBeanNames(
          "org.springframework.stereotype.Component",
          "org.springframework.context.annotation.Bean",
          "org.springframework.boot.autoconfigure.AutoConfiguration");

  private GeneralRules() {}

  static ArchRule uniqueBeanNames(String component, String bean, String autoConfiguration) {
    return classes()
        .should(new BeanNames(component, bean, autoConfiguration))
        .because("two beans with one name stop the application at startup");
  }

  /** Every general rule, for {@code @ArchTest} fields or manual checks. */
  public static List<ArchRule> all() {
    return List.of(
        NO_FIELD_INJECTION,
        NO_MUTABLE_STATE_IN_SINGLETONS,
        NO_SYSTEM_OUT,
        NO_GENERIC_EXCEPTIONS,
        NO_JAVA_UTIL_LOGGING,
        NO_JODA_TIME,
        NO_REACTOR_GLOBAL_HOOKS,
        NO_BLOCK_IN_REACTIVE_CODE,
        NO_TESTING_AUTHENTICATION,
        NO_PARALLEL_HTTP_CLIENTS,
        NO_AWS_SDK_V1,
        NO_TECHNOLOGY_ADAPTERS,
        NO_AUTOCONFIGURATION_DEPENDENCIES,
        KITS_ONLY_IN_PERSISTENCE_PACKAGES,
        NO_DEPRECATED_API,
        UNIQUE_BEAN_NAMES,
        NO_JVM_LOCKS_IN_BUSINESS_CODE,
        NO_SYNCHRONIZED_BUSINESS_METHODS);
  }

  static DescribedPredicate<JavaClass> annotatedWithAny(List<String> annotationNames) {
    return DescribedPredicate.describe(
        "annotated with a singleton stereotype",
        c ->
            annotationNames.stream()
                .anyMatch(a -> c.isAnnotatedWith(a) || c.isMetaAnnotatedWith(a)));
  }

  static DescribedPredicate<JavaField> named(String name) {
    return DescribedPredicate.describe("named " + name, f -> f.getName().equals(name));
  }
}
