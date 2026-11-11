package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.ApiOperation;
import pe.axiz.codegen.openapi.OpenApiDocument;

class AccessGenerationTest {
  static final String CONTRACT =
      """
      openapi: 3.1.0
      info: {title: Policy, version: 1.0.0, x-axiz-archetype: business}
      paths:
        /v1/policies/scoped:
          get:
            operationId: readScoped
            tags: [policies]
            security: [{oauth: [orders:read]}]
            responses: {'204': {description: Done}}
        /v1/policies/authenticated:
          get:
            operationId: readAuthenticated
            tags: [policies]
            security: [{jwt: []}]
            responses: {'204': {description: Done}}
        /v1/policies/public:
          get:
            operationId: readPublic
            tags: [policies]
            security: []
            responses: {'204': {description: Done}}
      components:
        securitySchemes:
          oauth:
            type: oauth2
            flows:
              clientCredentials:
                tokenUrl: https://issuer.test/token
                scopes: {orders:read: Read}
          jwt: {type: http, scheme: bearer}
      """;

  private ProjectRequest request(String contract, Stack stack) {
    return new ProjectRequest(
        "policy-orders",
        "pe.axiz.samples",
        "pe.axiz.samples.policy",
        "Access policy",
        "business",
        Structure.HEXAGONAL,
        stack,
        "aurora",
        List.of(),
        contract,
        null,
        "2.0.0");
  }

  @Test
  void scopedAuthenticatedAndPublicPoliciesGenerateBothStacksWithoutBlankExpressions() {
    var operations = OpenApiDocument.parse(CONTRACT).operations();
    assertEquals(
        List.of(
            ApiOperation.Access.SCOPED,
            ApiOperation.Access.AUTHENTICATED,
            ApiOperation.Access.PUBLIC),
        operations.stream().map(ApiOperation::access).toList());
    for (Stack stack : Stack.values()) {
      var project = ProjectGenerator.withDefaults().generate(request(CONTRACT, stack));
      String controller =
          project.text(
              "src/main/java/pe/axiz/samples/policy/adapter/in/rest/PoliciesController.java");
      assertTrue(
          controller.contains("@PreAuthorize(\"hasAuthority('SCOPE_orders:read')\")"), controller);
      assertTrue(controller.contains("@AuthenticatedAccess"), controller);
      assertTrue(controller.contains("@PublicEndpoint"), controller);
      assertFalse(controller.contains("@PreAuthorize(\"\")"));
      String config = project.text("src/main/resources/application.yaml");
      assertTrue(config.contains("/v1/policies/public"), config);
      assertFalse(config.contains("public-paths: /**"));
    }
  }

  @Test
  void rootPolicyIsInheritedAndOperationPolicyOverridesIt() {
    String inherited =
        CONTRACT
            .replaceAll("(?m)^      security:.*\\n", "")
            .replace("paths:\n", "security: [{oauth: [orders:read]}]\npaths:\n");
    assertTrue(
        OpenApiDocument.parse(inherited).operations().stream()
            .allMatch(op -> op.access() == ApiOperation.Access.SCOPED));
    String override =
        inherited.replace(
            "operationId: readPublic\n", "operationId: readPublic\n      security: []\n");
    assertEquals(
        ApiOperation.Access.PUBLIC,
        OpenApiDocument.parse(override).operations().getLast().access());
  }

  @Test
  void missingUnknownAndAmbiguousRequirementsFailBeforeWritingCode() {
    for (String policy :
        List.of(
            "",
            "security: [{missing: []}]",
            "security: [{oauth: [orders:read]}, {jwt: []}]",
            "security: [{oauth: [orders:read], jwt: []}]",
            "security: [{}]",
            "security: true",
            "security: [{jwt: [orders:read]}]")) {
      String contract = CONTRACT.replace("security: [{oauth: [orders:read]}]", policy);
      var result = ProjectGenerator.withDefaults().validate(request(contract, Stack.SERVLET));
      assertFalse(result.valid(), policy);
      assertTrue(
          result.problems().stream().anyMatch(problem -> problem.contains("readScoped")),
          result.toString());
    }
  }

  @Test
  void publicPatternsCannotSilentlyOpenOverlappingAuthenticatedOnlyRoutes() {
    var generator = ProjectGenerator.withDefaults();
    String overlap = CONTRACT.replace("/v1/policies/public:", "/v1/policies/{policy}:");
    assertTrue(
        generator.validate(request(overlap, Stack.REACTIVE)).problems().stream()
            .anyMatch(problem -> problem.contains("overlaps authenticated-only")));
    String disjoint = CONTRACT.replace("/v1/policies/public:", "/v1/status:");
    assertTrue(generator.validate(request(disjoint, Stack.REACTIVE)).valid());
  }
}
