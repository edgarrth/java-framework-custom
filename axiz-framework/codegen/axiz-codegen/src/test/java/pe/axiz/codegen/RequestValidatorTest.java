package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;

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
                    "business", Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of()))
            .valid());
  }

  @Test
  void reactiveServicesUseAuroraOverR2dbc() {
    for (Framework framework : List.of(Framework.CQRS)) {
      ProjectRequest r =
          Fixtures.request(
              "business",
              framework,
              Stack.REACTIVE,
              "aurora",
              List.of("outbox", "inbox", "scheduling"));
      assertTrue(generator.validate(r).valid(), generator.validate(r).problems().toString());
    }
    assertTrue(
        generator
            .validate(
                Fixtures.request(
                    "event-processor", Framework.EDA, Stack.REACTIVE, "aurora", List.of("outbox")))
            .valid());
  }

  @Test
  void rejectsIncompatibleCombinations() {
    assertTrue(
        mentions(
            problems(
                Fixtures.request("business", Framework.EDA, Stack.SERVLET, "aurora", List.of())),
            "supports frameworks"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Framework.HEXAGONAL, Stack.REACTIVE, "dynamodb", List.of())),
            "Stack reactive"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Framework.CQRS, Stack.REACTIVE, "aurora", List.of("saga"))),
            "need JPA, which blocks"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Framework.CQRS, Stack.REACTIVE, "aurora", List.of("sequence"))),
            "not available on aurora (reactive stack, R2DBC)"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Framework.CQRS, Stack.SERVLET, "aurora", List.of("r2dbc"))),
            "Capability r2dbc is not available"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request("experience", Framework.BFF, Stack.SERVLET, "aurora", List.of())),
            "no persistence"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "integration", Framework.HEXAGONAL, Stack.SERVLET, "none", List.of("outbox"))),
            "Capability outbox is not available on none"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "event-processor", Framework.EDA, Stack.SERVLET, "none", List.of("inbox"))),
            "it supports aurora"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Framework.HEXAGONAL, Stack.SERVLET, "dynamodb", List.of("saga"))),
            "need Aurora"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "business", Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of("teleport"))),
            "Unknown capability"));
    assertTrue(
        mentions(
            problems(
                Fixtures.request(
                    "library", Framework.NONE, Stack.SERVLET, "none", List.of("cache"))),
            "services only"));
  }

  @Test
  void restArchetypesNeedAValidContract() {
    ProjectRequest base =
        Fixtures.request("crud", Framework.LAYERED, Stack.SERVLET, "aurora", List.of());
    ProjectRequest missing =
        new ProjectRequest(
            base.name(),
            base.groupId(),
            base.basePackage(),
            "",
            "crud",
            Framework.LAYERED,
            Stack.SERVLET,
            "aurora",
            List.of(),
            null,
            null,
            "1.0.0");
    assertTrue(mentions(problems(missing), "OpenAPI 3.1 contract is required"));
    ProjectRequest broken =
        new ProjectRequest(
            base.name(),
            base.groupId(),
            base.basePackage(),
            "",
            "crud",
            Framework.LAYERED,
            Stack.SERVLET,
            "aurora",
            List.of(),
            "openapi: 3.1.0\npaths:\n  /orders:\n    get:\n      responses: {}\n",
            null,
            "1.0.0");
    assertFalse(generator.validate(broken).valid(), "lint errors block the generation");
  }

  @Test
  void rejectsUnsafeNames() {
    ProjectRequest base =
        Fixtures.request("crud", Framework.LAYERED, Stack.SERVLET, "aurora", List.of());
    ProjectRequest evil =
        new ProjectRequest(
            "../../etc",
            "pe.axiz",
            "pe.axiz.x",
            "",
            "crud",
            Framework.LAYERED,
            Stack.SERVLET,
            "aurora",
            List.of(),
            base.openApi(),
            null,
            "1.0.0");
    assertTrue(mentions(problems(evil), "kebab-case"));
    assertThrows(GenerationException.class, () -> generator.generate(evil));
  }

  @Test
  void descriptionsAreOneLineOfAnyPunctuation() {
    ProjectRequest base =
        Fixtures.request("crud", Framework.LAYERED, Stack.SERVLET, "aurora", List.of());
    for (String text : List.of("Payments --- reconciliation <v2> & more", "x".repeat(200))) {
      assertTrue(generator.validate(withDescription(base, text)).valid(), text);
    }
    for (String text : List.of("two\nlines", "tab\tinside", "x".repeat(201))) {
      assertTrue(mentions(problems(withDescription(base, text)), "single line"), text);
    }
  }

  private static ProjectRequest withDescription(ProjectRequest b, String description) {
    return new ProjectRequest(
        b.name(),
        b.groupId(),
        b.basePackage(),
        description,
        b.archetype(),
        b.framework(),
        b.stack(),
        b.persistence(),
        b.capabilities(),
        b.openApi(),
        b.asyncApi(),
        b.frameworkVersion());
  }
}
