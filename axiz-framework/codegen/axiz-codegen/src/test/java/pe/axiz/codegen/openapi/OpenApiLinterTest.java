package pe.axiz.codegen.openapi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.Fixtures;

class OpenApiLinterTest {

  @Test
  void theSampleContractHasNoErrors() {
    List<LintFinding> findings = OpenApiLinter.lint(OpenApiDocument.parse(Fixtures.orders()));
    assertTrue(findings.stream().noneMatch(LintFinding::error), findings.toString());
  }

  @Test
  void reportsTheCorporateRules() {
    String bad =
        """
        openapi: 3.1.0
        info: {title: Bad, version: '1'}
        paths:
          /Orders:
            get:
              operationId: List-Orders
              parameters:
                - {name: cardNumber, in: query, schema: {type: string}}
              responses:
                '200': {description: ok}
                '404':
                  description: missing
                  content:
                    application/json: {schema: {type: object}}
            post:
              responses:
                '201': {description: created}
        """;
    List<String> rules =
        OpenApiLinter.lint(OpenApiDocument.parse(bad)).stream().map(LintFinding::rule).toList();
    for (String rule :
        List.of(
            "axiz-paths-kebab-case",
            "axiz-versioned-paths",
            "axiz-operation-id-camel-case",
            "axiz-operation-id-required",
            "axiz-problem-json-errors",
            "axiz-no-business-ids-in-query")) {
      assertTrue(rules.contains(rule), rule + " in " + rules);
    }
  }

  @Test
  void ruleIdsMatchTheSpectralRuleset() throws Exception {
    Path ruleset =
        Path.of("")
            .toAbsolutePath()
            .getParent()
            .getParent()
            .resolve(
                "quality/axiz-code-standards/src/main/resources/axiz/spectral/axiz-openapi.yaml");
    if (!Files.exists(ruleset)) {
      return; // fuera del repositorio
    }
    String spectral = Files.readString(ruleset);
    for (String rule :
        List.of(
            "axiz-operation-id-camel-case",
            "axiz-paths-kebab-case",
            "axiz-versioned-paths",
            "axiz-problem-json-errors",
            "axiz-examples-required",
            "axiz-no-business-ids-in-query",
            "axiz-idempotency-key-on-post")) {
      assertTrue(spectral.contains(rule + ":"), rule);
    }
  }
}
