package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

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

  private static final DescribedPredicate<JavaClass> SINGLETON =
      annotatedWithAny(SINGLETON_STEREOTYPES)
          .or(
              DescribedPredicate.describe(
                  "returned by a bean method",
                  type ->
                      type.getMethodsWithReturnTypeOfSelf().stream()
                          .anyMatch(
                              method ->
                                  method.isAnnotatedWith(
                                      "org.springframework.context.annotation.Bean"))));

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
          .areDeclaredInClassesThat(SINGLETON)
          .and()
          .areNotStatic()
          .should()
          .beFinal()
          .because("request data must never be stored in singleton fields");

  /** Review gate for shared mutable declared types, including injected collections. */
  public static final ArchRule SINGLETON_FIELDS_OF_MUTABLE_TYPE_ARE_REVIEWED =
      fields()
          .that()
          .areDeclaredInClassesThat(SINGLETON)
          .and()
          .areNotStatic()
          .and()
          .haveRawType(ReviewedFields.MUTABLE)
          .should(new ReviewedFields());

  /**
   * Packages that hold business data or carry it across a boundary: domain and application models
   * and the transport records of inbound and outbound adapters.
   */
  private static final String[] DATA_PACKAGES = {
    "..domain..",
    "..application..",
    "..usecase..",
    "..model..",
    "..service..",
    "..port..",
    "..adapter..",
    "..controller..",
    "..dto..",
    "..api..",
    "..web..",
    "..rest..",
    "..client..",
    "..messaging..",
    "..handler.."
  };

  private static final DescribedPredicate<JavaClass> BINARY_FLOATING_POINT =
      DescribedPredicate.describe(
          "binary floating point",
          type ->
              Set.of("float", "double", "java.lang.Float", "java.lang.Double")
                  .contains(type.getName()));

  /** Binary floating point cannot model money or other business quantities, nor carry them. */
  public static final ArchRule NO_FLOATING_POINT_IN_BUSINESS_MODELS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(DATA_PACKAGES)
          .should()
          .haveRawType(BINARY_FLOATING_POINT);

  /** The same for method parameters and results: a {@code double amount} argument loses cents. */
  public static final ArchRule NO_FLOATING_POINT_IN_BUSINESS_SIGNATURES =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(DATA_PACKAGES)
          .should(
              new ArchCondition<JavaMethod>("not take or return binary floating point") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  boolean floating =
                      BINARY_FLOATING_POINT.test(method.getRawReturnType())
                          || method.getRawParameterTypes().stream().anyMatch(BINARY_FLOATING_POINT);
                  if (floating)
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " uses binary floating point; use BigDecimal"));
                }
              });

  public static final ArchRule NO_DOUBLE_DECIMAL_CONSTRUCTION =
      noClasses()
          .should()
          .callConstructor(BigDecimal.class, double.class)
          .orShould()
          .callMethod(BigDecimal.class, "valueOf", double.class);

  private static final Set<String> TEMPORAL_TYPES =
      Set.of(
          "java.time.Instant",
          "java.time.LocalDate",
          "java.time.LocalDateTime",
          "java.time.ZonedDateTime",
          "java.time.OffsetDateTime",
          "java.time.OffsetTime",
          "java.time.LocalTime",
          "java.time.Year",
          "java.time.YearMonth",
          "java.time.MonthDay");

  /**
   * Ambient clocks prevent reproducible domain and application behavior. {@code now()} and {@code
   * now(ZoneId)} read the system clock, as do {@code Clock.systemUTC()} and its siblings; {@code
   * now(clock)} with an injected {@link java.time.Clock} is the intended form.
   */
  public static final ArchRule BUSINESS_USES_INJECTED_CLOCK =
      noClasses()
          .that()
          .resideInAnyPackage(
              "..domain..",
              "..model..",
              "..application..",
              "..usecase..",
              "..service..",
              "..handler..",
              "..job..")
          .should()
          .callMethodWhere(
              DescribedPredicate.describe(
                  "ambient clock",
                  call -> {
                    String owner = call.getTargetOwner().getName();
                    List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
                    return (owner.equals("java.lang.System")
                            && call.getName().equals("currentTimeMillis"))
                        || (owner.equals("java.time.Clock")
                            && Set.of("systemUTC", "systemDefaultZone", "system")
                                .contains(call.getName()))
                        || (TEMPORAL_TYPES.contains(owner)
                            && call.getName().equals("now")
                            && (parameters.isEmpty()
                                || parameters.get(0).getName().equals("java.time.ZoneId")));
                  }));

  private static final Set<String> JCA_ENGINES =
      Set.of(
          "java.security.MessageDigest",
          "java.security.Signature",
          "java.security.KeyPairGenerator",
          "javax.crypto.Cipher",
          "javax.crypto.Mac",
          "javax.crypto.KeyGenerator",
          "javax.crypto.SecretKeyFactory");

  /**
   * Services hash, sign, encrypt and draw random values through {@code axiz-core-crypto} ({@code
   * Hashing}, {@code AesGcm}, {@code FieldEncryptor}, {@code SecureRandoms}): algorithm, mode and
   * randomness are reviewed once there, instead of choosing MD5, ECB or {@code java.util.Random} at
   * each call site.
   */
  public static final ArchRule NO_RAW_CRYPTOGRAPHY_OR_PREDICTABLE_RANDOMNESS =
      noClasses()
          .should()
          .callMethodWhere(
              DescribedPredicate.describe(
                  "a JCA engine or Math.random",
                  call ->
                      (JCA_ENGINES.contains(call.getTargetOwner().getName())
                              && call.getName().equals("getInstance"))
                          || (call.getTargetOwner().getName().equals("java.lang.Math")
                              && call.getName().equals("random"))))
          .orShould()
          .callConstructorWhere(
              DescribedPredicate.describe(
                  "java.util.Random",
                  call -> call.getTargetOwner().getName().equals("java.util.Random")))
          .because(
              "algorithms, modes and random sources are reviewed once in axiz-core-crypto"
                  + " (Hashing, AesGcm, FieldEncryptor, SecureRandoms)");

  public static final ArchRule NO_TRANSACTIONAL_TRANSPORT_OR_DOMAIN_CLASSES =
      noClasses()
          .that()
          .resideInAnyPackage("..domain..", "..controller..", "..adapter.in..")
          .should()
          .beMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional");

  public static final ArchRule NO_TRANSACTIONAL_TRANSPORT_OR_DOMAIN_METHODS =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage("..domain..", "..controller..", "..adapter.in..")
          .should()
          .notBeMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional");

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
        SINGLETON_FIELDS_OF_MUTABLE_TYPE_ARE_REVIEWED,
        NO_FLOATING_POINT_IN_BUSINESS_MODELS,
        NO_FLOATING_POINT_IN_BUSINESS_SIGNATURES,
        NO_DOUBLE_DECIMAL_CONSTRUCTION,
        BUSINESS_USES_INJECTED_CLOCK,
        NO_RAW_CRYPTOGRAPHY_OR_PREDICTABLE_RANDOMNESS,
        NO_TRANSACTIONAL_TRANSPORT_OR_DOMAIN_CLASSES,
        NO_TRANSACTIONAL_TRANSPORT_OR_DOMAIN_METHODS,
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
}
