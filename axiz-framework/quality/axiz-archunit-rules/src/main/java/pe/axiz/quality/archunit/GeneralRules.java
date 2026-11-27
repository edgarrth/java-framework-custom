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
 * Reglas de higiene que siguen todos los servicios y módulos de axiz (sección 16 de la definición).
 * Los tipos de anotación se referencian por nombre para que las reglas no necesiten Spring en el
 * classpath.
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

  /** Las dependencias se inyectan solo mediante constructores. */
  public static final ArchRule NO_FIELD_INJECTION =
      noFields()
          .should()
          .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
          .orShould()
          .beAnnotatedWith("jakarta.inject.Inject")
          .orShould()
          .beAnnotatedWith("org.springframework.beans.factory.annotation.Value")
          .because("constructor injection makes dependencies explicit and beans immutable");

  /** Los beans singleton no guardan estado mutable entre solicitudes. */
  public static final ArchRule NO_MUTABLE_STATE_IN_SINGLETONS =
      fields()
          .that()
          .areDeclaredInClassesThat(SINGLETON)
          .and()
          .areNotStatic()
          .should()
          .beFinal()
          .because("request data must never be stored in singleton fields");

  /**
   * Control de revisión para los tipos declarados mutables y compartidos, incluidas las colecciones
   * inyectadas.
   */
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
   * Paquetes que contienen datos de negocio o los transportan a través de una frontera: los modelos
   * de dominio y de aplicación y los records de transporte de los adaptadores de entrada y de
   * salida.
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

  /**
   * El punto flotante binario no puede modelar dinero ni otras cantidades de negocio, ni
   * transportarlas.
   */
  public static final ArchRule NO_FLOATING_POINT_IN_BUSINESS_MODELS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(DATA_PACKAGES)
          .should()
          .haveRawType(BINARY_FLOATING_POINT);

  /**
   * Lo mismo para los parámetros y resultados de métodos: un argumento {@code double amount} pierde
   * centavos.
   */
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
   * Los relojes ambientales impiden un comportamiento de dominio y de aplicación reproducible.
   * {@code now()} y {@code now(ZoneId)} leen el reloj del sistema, al igual que {@code
   * Clock.systemUTC()} y sus variantes; {@code now(clock)} con un {@link java.time.Clock} inyectado
   * es la forma prevista.
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
   * Los servicios calculan hashes, firman, cifran y obtienen valores aleatorios mediante {@code
   * axiz-core-crypto} ({@code Hashing}, {@code AesGcm}, {@code FieldEncryptor}, {@code
   * SecureRandoms}): el algoritmo, el modo y la aleatoriedad se revisan una sola vez allí, en lugar
   * de elegir MD5, ECB o {@code java.util.Random} en cada punto de llamada.
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

  /** Sin hooks globales de Reactor; Micrometer se encarga de propagar el contexto. */
  public static final ArchRule NO_REACTOR_GLOBAL_HOOKS =
      noClasses()
          .should()
          .callMethodWhere(
              DescribedPredicate.describe(
                  "Reactor Hooks",
                  call -> call.getTargetOwner().getName().equals("reactor.core.publisher.Hooks")))
          .because("global hooks leak context and slow down every operator");

  /** Se prohíben las llamadas bloqueantes en los pipelines reactivos. */
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

  /** Paquetes de operaciones de negocio en cada estilo (casos de uso, handlers, servicios). */
  private static final String[] BUSINESS_PACKAGES = {
    "..application..", "..usecase..", "..service..", "..handler..", "..job.."
  };

  /**
   * Las operaciones de negocio nunca se serializan con locks locales de la JVM: un servicio ejecuta
   * varias réplicas, por lo que un {@code ReentrantLock} o un método {@code synchronized} protege
   * un solo pod y dos réplicas pueden igualmente mover la misma cuenta a la vez. Se usa el lock
   * distribuido ({@code LockTemplate} de axiz-lock) o restricciones de base de datos.
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

  /** La misma regla para los métodos {@code synchronized}. */
  public static final ArchRule NO_SYNCHRONIZED_BUSINESS_METHODS =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(BUSINESS_PACKAGES)
          .should()
          .notHaveModifier(JavaModifier.SYNCHRONIZED)
          .because(
              "a lock in one JVM does not serialize the replicas of a service; use LockTemplate");

  /** Los tokens de autenticación de prueba nunca llegan al código de producción. */
  public static final ArchRule NO_TESTING_AUTHENTICATION =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(
              "org.springframework.security.authentication.TestingAuthenticationToken");

  /** No se permiten clientes HTTP paralelos; se usan interfaces @HttpExchange. */
  public static final ArchRule NO_PARALLEL_HTTP_CLIENTS =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("retrofit2..", "feign..", "okhttp3..")
          .because("axiz standardizes on Spring HTTP interface clients");

  /** Se prohíbe la versión 1 del AWS SDK. */
  public static final ArchRule NO_AWS_SDK_V1 =
      noClasses().should().dependOnClassesThat().resideInAPackage("com.amazonaws..");

  /**
   * Los servicios consumen puertos y fachadas públicas de los starters; los adaptadores se cablean
   * en tiempo de ejecución.
   */
  public static final ArchRule NO_TECHNOLOGY_ADAPTERS =
      noClasses().should().dependOnClassesThat().resideInAPackage("pe.axiz.adapter..");

  /**
   * Las clases de autoconfiguración pertenecen al cableado del framework, no a las dependencias del
   * servicio.
   */
  public static final ArchRule NO_AUTOCONFIGURATION_DEPENDENCIES =
      noClasses().should().dependOnClassesThat().haveSimpleNameEndingWith("AutoConfiguration");

  /**
   * Los kits de tecnología se restringen a los adaptadores de persistencia de salida y a los
   * repositorios.
   */
  public static final ArchRule KITS_ONLY_IN_PERSISTENCE_PACKAGES =
      noClasses()
          .that()
          .resideOutsideOfPackages("..adapter.out.persistence..", "..repository..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("pe.axiz.kit..");

  /**
   * Un framework nuevo parte sin código obsoleto: nada puede invocar, extender ni anotar con una
   * API que su propietario marcó como {@code @Deprecated} (Spring, AWS SDK, Jackson, el propio
   * axiz).
   */
  public static final ArchRule NO_DEPRECATED_API =
      GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED;

  /**
   * Cada componente escaneado tiene un nombre de bean propio. Una clase {@code @Configuration} con
   * el mismo nombre que uno de sus métodos {@code @Bean} (la clase {@code SettlementJob} con {@code
   * Job settlementJob()}) compila y supera las pruebas unitarias, pero la aplicación no arranca.
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

  /** Todas las reglas generales, para campos {@code @ArchTest} o verificaciones manuales. */
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
