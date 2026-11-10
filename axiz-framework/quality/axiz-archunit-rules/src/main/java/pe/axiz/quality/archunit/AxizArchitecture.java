package pe.axiz.quality.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Composes role, stack, structure and extension rules for a service. */
public final class AxizArchitecture {
  private final String basePackage;
  private Archetype archetype = Archetype.BUSINESS;
  private Stack stack = Stack.SERVLET;
  private Structure structure = Structure.HEXAGONAL;
  private Set<Extension> extensions = Set.of();

  private AxizArchitecture(String basePackage) {
    this.basePackage = Objects.requireNonNull(basePackage, "basePackage");
    if (basePackage.isBlank()) throw new IllegalArgumentException("basePackage is required");
  }

  public static AxizArchitecture of(String basePackage) {
    return new AxizArchitecture(basePackage);
  }

  public AxizArchitecture archetype(Archetype value) {
    archetype = Objects.requireNonNull(value);
    return this;
  }

  public AxizArchitecture stack(Stack value) {
    stack = Objects.requireNonNull(value);
    return this;
  }

  public AxizArchitecture structure(Structure value) {
    structure = Objects.requireNonNull(value);
    return this;
  }

  public AxizArchitecture extensions(Extension... values) {
    extensions = Set.of(values);
    return this;
  }

  public List<ArchRule> rules() {
    validate();
    List<ArchRule> rules = new ArrayList<>(GeneralRules.all());
    rules.addAll(ArchetypeRules.rules(archetype));
    if (stack == Stack.REACTIVE) rules.addAll(ReactiveRules.rules());
    rules.addAll(structureRules(basePackage, structure));
    if (extensions.contains(Extension.CQRS)) rules.addAll(CqrsRules.rules(basePackage));
    return List.copyOf(rules);
  }

  private void validate() {
    boolean compatible =
        switch (archetype) {
          case BUSINESS, SUPPORT ->
              structure == Structure.HEXAGONAL || structure == Structure.LAYERED;
          case CORE, GATEWAY -> structure == Structure.HEXAGONAL;
          case UX -> structure == Structure.BFF;
          case EVENT_PROCESSOR -> structure == Structure.EDA || structure == Structure.HEXAGONAL;
          case BATCH -> structure == Structure.BATCH && stack == Stack.SERVLET;
        };
    if (!compatible)
      throw new IllegalArgumentException("Incompatible archetype, structure and stack");
    if (!extensions.isEmpty()
        && (structure != Structure.HEXAGONAL
            || (archetype != Archetype.BUSINESS && archetype != Archetype.EVENT_PROCESSOR))) {
      throw new IllegalArgumentException("CQRS requires hexagonal business or event-processor");
    }
  }

  private static List<ArchRule> structureRules(String pkg, Structure structure) {
    return switch (structure) {
      case LAYERED -> LayeredRules.rules(pkg);
      case HEXAGONAL -> HexagonalRules.rules(pkg);
      case EDA -> EdaRules.rules(pkg);
      case BFF -> BffRules.rules(pkg);
      case BATCH -> BatchRules.rules(pkg);
    };
  }

  public void verify() {
    verify(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(basePackage, "pe.axiz.core")
            .that(
                DescribedPredicate.describe(
                    "service production classes",
                    type -> type.getName().startsWith(basePackage + "."))));
  }

  public void verify(JavaClasses classes) {
    if (classes.isEmpty())
      throw new IllegalArgumentException("No production classes found for " + basePackage);
    List<String> failures = new ArrayList<>();
    for (ArchRule rule : rules()) {
      var result = rule.allowEmptyShould(true).evaluate(classes);
      if (result.hasViolation()) failures.add(result.getFailureReport().toString());
    }
    if (!failures.isEmpty()) throw new AssertionError(String.join("\n\n", failures));
  }
}
