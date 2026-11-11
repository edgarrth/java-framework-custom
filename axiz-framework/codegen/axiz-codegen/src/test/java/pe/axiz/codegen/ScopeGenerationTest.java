package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pe.axiz.codegen.openapi.OpenApiDocument;

class ScopeGenerationTest {
  private static final String CONTRACT =
      """
      openapi: 3.0.3
      info: {title: Payment, version: 1.0.0}
      security:
        - oauth: [payments:read, payments:write]
      components:
        securitySchemes:
          oauth:
            type: oauth2
            flows:
              clientCredentials:
                tokenUrl: https://issuer.test/token
                scopes: {payments:read: Read, payments:write: Write}
      paths:
        /payments:
          post:
            operationId: pay
            responses:
              '204': {description: Accepted}
      """;

  @Test
  void allScopesInOneRequirementAreGeneratedAsAnd() {
    var operation = OpenApiDocument.parse(CONTRACT).operations().getFirst();
    assertEquals(
        "hasAuthority('SCOPE_payments:read') and hasAuthority('SCOPE_payments:write')",
        ProjectGenerator.operation(operation, "example.payments").get("authorizationExpression"));
  }

  @Test
  void alternativesRequireAnExplicitManualPolicy() {
    var document =
        OpenApiDocument.parse(
            CONTRACT.replace(
                "- oauth: [payments:read, payments:write]",
                "- oauth: [payments:read]\n  - oauth: [payments:write]"));
    assertEquals(
        "",
        ProjectGenerator.operation(document.operations().getFirst(), "example.payments")
            .get("authorizationExpression"));
  }
}
