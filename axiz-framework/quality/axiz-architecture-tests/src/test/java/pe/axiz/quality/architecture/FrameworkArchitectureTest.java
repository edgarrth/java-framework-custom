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

  /** Request/event code cannot disguise blocking I/O with a worker scheduler. */
  @ArchTest
  static final ArchRule FRAMEWORK_DOES_NOT_OFFLOAD_BLOCKING_IO =
      noClasses()
          .that()
          .resideInAnyPackage("pe.axiz.spring..", "pe.axiz.structure..", "pe.axiz.extension..")
          .should()
          .callMethodWhere(
              new DescribedPredicate<>("create or use an elastic worker scheduler") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                  return call.getTargetOwner().getName().equals("reactor.core.scheduler.Schedulers")
                      && java.util.Set.of(
                              "boundedElastic", "newBoundedElastic", "elastic", "newElastic")
                          .contains(call.getName());
                }
              })
          .because(
              "architecture contract requires native asynchronous I/O, including CQRS and outbound authentication");

  /** Layer 1 is plain Java: no Spring, no Quarkus, no upper layers. */
  @ArchTest
  static final ArchRule CORE_IS_PLAIN_JAVA =
      noClasses()
          .that()
          .resideInAPackage("pe.axiz.core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "io.quarkus..",
              "pe.axiz.spring..",
              "pe.axiz.structure..",
              "pe.axiz.extension..",
              "pe.axiz.adapter..",
              "pe.axiz.kit..",
              "software.amazon.awssdk..",
              "io.lettuce..",
              "org.apache.kafka..",
              "io.r2dbc..",
              "jakarta.persistence..",
              "com.nimbusds..",
              "reactor..",
              "org.reactivestreams..")
          .because("layer 1 is framework-neutral so a Quarkus extension can wire the same core");

  /** Layer 2 never depends on layer 3. */
  @ArchTest
  static final ArchRule SPRING_DOES_NOT_KNOW_FRAMEWORKS =
      noClasses()
          .that()
          .resideInAPackage("pe.axiz.spring..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("pe.axiz.structure..", "pe.axiz.extension..");

  /** Framework contracts (-api) are plain Java over layer 1. */
  @ArchTest
  static final ArchRule FRAMEWORK_APIS_ARE_PLAIN_JAVA =
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
  static final ArchRule STARTERS_ARE_ISOLATED =
      ArchRuleDefinition.classes()
          .that()
          .resideInAPackage("pe.axiz.spring..")
          .should(onlyUseOwnCapabilityOrFoundation());

  /** No cycles between core capabilities. */
  @ArchTest
  static final ArchRule CORE_HAS_NO_CYCLES =
      slices().matching("pe.axiz.core.(*)..").should().beFreeOfCycles();

  /** No cycles between framework styles. */
  @ArchTest
  static final ArchRule FRAMEWORKS_HAVE_NO_CYCLES =
      slices().matching("pe.axiz.structure.(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule TECHNOLOGY_HAS_NO_RUNTIME =
      noClasses()
          .that()
          .resideInAnyPackage("pe.axiz.adapter..", "pe.axiz.kit..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "pe.axiz.spring..",
              "pe.axiz.structure..",
              "pe.axiz.extension..",
              "org.springframework..",
              "io.quarkus..");

  @ArchTest
  static final ArchRule ADAPTERS_ONLY_IMPLEMENT_THEIR_CONTRACT =
      ArchRuleDefinition.classes()
          .that()
          .resideInAPackage("pe.axiz.adapter..")
          .should(
              new ArchCondition<>(
                  "only use their contract, technology kit and AWS instrumentation") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                  String own = item.getPackageName().substring("pe.axiz.adapter.".length());
                  String[] segments = own.split("\\.");
                  String capability = segments[0] + "." + segments[1];
                  item.getDirectDependenciesFromSelf()
                      .forEach(
                          d -> {
                            String target = d.getTargetClass().getPackageName();
                            if (target.startsWith("pe.axiz.adapter.")
                                && !target.equals("pe.axiz.adapter." + capability)
                                && !target.startsWith("pe.axiz.adapter." + capability + ".")
                                && !(aws(item)
                                    && target.startsWith("pe.axiz.adapter.observability.aws"))) {
                              events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                            }
                          });
                }
              });

  @ArchTest
  static final ArchRule REACTOR_API_ONLY_IN_R2DBC_TECHNOLOGY =
      ArchRuleDefinition.classes()
          .that()
          .resideInAnyPackage("pe.axiz.adapter..", "pe.axiz.kit..")
          .should(
              new ArchCondition<>("keep Reactor in native R2DBC and RS in SDK interoperability") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                  if (r2dbc(item) || !publiclyVisible(item)) return;
                  item.getDirectDependenciesFromSelf()
                      .forEach(
                          d -> {
                            String target = d.getTargetClass().getPackageName();
                            if (target.startsWith("reactor.")
                                || ((target.equals("org.reactivestreams")
                                        || target.startsWith("org.reactivestreams."))
                                    && !aws(item))) {
                              events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                            }
                          });
                }
              });

  @ArchTest
  static final ArchRule TECHNOLOGY_PUBLIC_API_USES_PORT_TYPES =
      ArchRuleDefinition.classes()
          .that()
          .resideInAnyPackage("pe.axiz.adapter..", "pe.axiz.kit..")
          .should(
              new ArchCondition<>("expose port types in public signatures") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                  if (r2dbc(item) || !publiclyVisible(item)) return;
                  item.getMethods().stream()
                      .filter(
                          m ->
                              m.getModifiers()
                                  .contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC))
                      .forEach(
                          m -> {
                            java.util.List<JavaClass> types =
                                new java.util.ArrayList<>(
                                    m.getReturnType().getAllInvolvedRawTypes());
                            types.add(m.getRawReturnType());
                            types.addAll(m.getRawParameterTypes());
                            m.getParameterTypes()
                                .forEach(
                                    parameter -> types.addAll(parameter.getAllInvolvedRawTypes()));
                            types.stream()
                                .filter(
                                    t ->
                                        t.getPackageName().startsWith("reactor.")
                                            || (t.getPackageName().equals("org.reactivestreams")
                                                || t.getPackageName()
                                                    .startsWith("org.reactivestreams.")))
                                .forEach(
                                    t ->
                                        events.add(
                                            SimpleConditionEvent.violated(
                                                m, m.getFullName() + " exposes " + t.getName())));
                          });
                }
              });

  static boolean publiclyVisible(JavaClass type) {
    if (!type.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC))
      return false;
    return type.getEnclosingClass().map(FrameworkArchitectureTest::publiclyVisible).orElse(true);
  }

  private static boolean aws(JavaClass type) {
    return type.getPackageName()
        .matches("pe\\.axiz\\.adapter\\..*\\.(aws|dynamodb|s3|sqs|sns|kms)(\\..*)?");
  }

  private static boolean r2dbc(JavaClass type) {
    return type.getPackageName()
        .matches("pe\\.axiz\\.(kit\\.r2dbc|adapter\\.(inbox|outbox)\\.r2dbc)(\\..*)?");
  }

  static DescribedPredicate<JavaClass> apiPackages() {
    return DescribedPredicate.describe(
        "framework api classes",
        c ->
            (c.getPackageName().startsWith("pe.axiz.structure.")
                    || c.getPackageName().startsWith("pe.axiz.extension."))
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
