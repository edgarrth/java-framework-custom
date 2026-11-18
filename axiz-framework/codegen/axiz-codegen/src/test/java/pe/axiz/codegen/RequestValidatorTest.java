package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

class RequestValidatorTest {

  private final ProjectGenerator generator = ProjectGenerator.withDefaults();

  private List<String> problems(ProjectRequest r) {
    return generator.validate(r).problems();
  }

  private static boolean mentions(List<String> problems, String text) {
    return problems.stream().anyMatch(p -> p.contains(text));
  }

  @Test
  void acceptsTheDefaults() {
    assertTrue(
        generator
            .validate(
                Fixtures.request(
                    "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of()))
            .valid());
  }

  @Test
  void reactiveServicesUseAuroraOverR2dbc() {
    for (Structure structure : List.of(Structure.HEXAGONAL)) {
      ProjectRequest r =
          Fixtures.request(
              "business",
              structure,
              Stack.REACTIVE,
              "aurora",
              List.of("outbox", "inbox", "scheduling", "messaging-kafka"));
      assertTrue(generator.validate(r).valid(), generator.validate(r).problems().toString());
    }
    assertTrue(
        generator
            .validate(
                Fixtures.request(
                    "event-processor", Structure.EDA, Stack.REACTIVE, "aurora", List.of("outbox")))
            .valid());
  }

  @Test
  void rejectsIncompatibleCombinations() {
    assertTrue(
        mentions(
            problems(
                Fixtures.request("business", Structure.EDA, Stack.SERVLET, "aurora", List.of())),
            "supports structures"));
    assertTrue(
        generator
            .validate(
                Fixtures.request(
                    "business", Structure.HEXAGONAL, Stack.REACTIVE, "dynamodb", List.of()))
            .valid());
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Structure.HEXAGONAL, Stack.REACTIVE, "aurora", List.of("saga"))),
            "need JPA, which blocks"));
    assertTrue(
        generator
            .validate(
                Fixtures.request(
                    "business", Structure.HEXAGONAL, Stack.REACTIVE, "aurora", List.of("sequence")))
            .valid());
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of("r2dbc"))),
            "Capability r2dbc is not available"));
    assertTrue(
        mentions(
            problems(Fixtures.request("ux", Structure.BFF, Stack.SERVLET, "aurora", List.of())),
            "supports persistence"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "core", Structure.HEXAGONAL, Stack.SERVLET, "none", List.of("outbox"))),
            "Capability outbox is not available on none"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "event-processor", Structure.EDA, Stack.SERVLET, "none", List.of("inbox"))),
            "Capability inbox is not available on none"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Structure.HEXAGONAL, Stack.SERVLET, "dynamodb", List.of("saga"))),
            "need Aurora"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of("teleport"))),
            "Unknown capability"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "library", Structure.NONE, Stack.SERVLET, "none", List.of("cache"))),
            "services only"));
  }

  @Test
  void restArchetypesNeedAValidContract() {
    ProjectRequest base =
        Fixtures.request("business", Structure.LAYERED, Stack.SERVLET, "aurora", List.of());
    ProjectRequest missing =
        ProjectRequest.builder()
            .name(base.name())
            .groupId(base.groupId())
            .basePackage(base.basePackage())
            .description("")
            .archetype("business")
            .structure(Structure.LAYERED)
            .stack(Stack.SERVLET)
            .persistence("aurora")
            .capabilities(List.of())
            .openApi(null)
            .asyncApi(null)
            .frameworkVersion("2.0.0")
            .extensions(List.of())
            .build();
    assertTrue(mentions(problems(missing), "OpenAPI 3.1 contract is required"));
    ProjectRequest broken =
        ProjectRequest.builder()
            .name(base.name())
            .groupId(base.groupId())
            .basePackage(base.basePackage())
            .description("")
            .archetype("business")
            .structure(Structure.LAYERED)
            .stack(Stack.SERVLET)
            .persistence("aurora")
            .capabilities(List.of())
            .openApi("openapi: 3.1.0\npaths:\n  /orders:\n    get:\n      responses: {}\n")
            .asyncApi(null)
            .frameworkVersion("2.0.0")
            .extensions(List.of())
            .build();
    assertFalse(generator.validate(broken).valid(), "lint errors block the generation");
  }

  @Test
  void rejectsUnsafeNames() {
    ProjectRequest base =
        Fixtures.request("business", Structure.LAYERED, Stack.SERVLET, "aurora", List.of());
    ProjectRequest evil =
        ProjectRequest.builder()
            .name("../../etc")
            .groupId("pe.axiz")
            .basePackage("pe.axiz.x")
            .description("")
            .archetype("business")
            .structure(Structure.LAYERED)
            .stack(Stack.SERVLET)
            .persistence("aurora")
            .capabilities(List.of())
            .openApi(base.openApi())
            .asyncApi(null)
            .frameworkVersion("2.0.0")
            .extensions(List.of())
            .build();
    assertTrue(mentions(problems(evil), "kebab-case"));
    assertThrows(GenerationException.class, () -> generator.generate(evil));
  }

  @Test
  void descriptionsAreOneLineOfAnyPunctuation() {
    ProjectRequest base =
        Fixtures.request("business", Structure.LAYERED, Stack.SERVLET, "aurora", List.of());
    for (String text : List.of("Payments --- reconciliation <v2> & more", "x".repeat(200))) {
      assertTrue(generator.validate(withDescription(base, text)).valid(), text);
    }
    for (String text : List.of("two\nlines", "tab\tinside", "x".repeat(201))) {
      assertTrue(mentions(problems(withDescription(base, text)), "single line"), text);
    }
  }

  private static ProjectRequest withDescription(ProjectRequest b, String description) {
    return ProjectRequest.builder()
        .name(b.name())
        .groupId(b.groupId())
        .basePackage(b.basePackage())
        .description(description)
        .archetype(b.archetype())
        .structure(b.structure())
        .stack(b.stack())
        .persistence(b.persistence())
        .capabilities(b.capabilities())
        .openApi(b.openApi())
        .asyncApi(b.asyncApi())
        .frameworkVersion(b.frameworkVersion())
        .extensions(List.of())
        .build();
  }
}
