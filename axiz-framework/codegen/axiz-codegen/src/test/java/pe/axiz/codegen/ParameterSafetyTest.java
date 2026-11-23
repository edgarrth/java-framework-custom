package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.openapi.Json;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;

class ParameterSafetyTest {
  @Test
  void rejectsCodeAndUnicodeEscapesAtOperationPathAndLocalReferenceBoundaries() {
    for (String name :
        List.of("x\") String injected) { } //", "x\nvalue", "x\\u0022", "a".repeat(65))) {
      for (String location : List.of("operation", "path", "reference")) {
        var document = document(name, location);
        assertTrue(
            OpenApiLinter.lint(document).stream()
                .anyMatch(
                    finding ->
                        finding.error() && finding.rule().equals("axiz-safe-parameter-names")));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectGenerator.operation(document.operations().getFirst(), "com.example"));
      }
    }
  }

  @Test
  void retainsOrdinaryHttpNamesAndChecksReferencedSensitiveQueries() {
    for (String name : List.of("filter-name", "filter.name", "filter_name", "a", "a".repeat(64))) {
      var document = document(name, "reference");
      assertFalse(OpenApiLinter.lint(document).stream().anyMatch(finding -> finding.error()));
      assertTrue(
          ProjectGenerator.operation(document.operations().getFirst(), "com.example")
              .get("controllerParams")
              .toString()
              .contains("name = " + Json.write(name)));
    }
    assertTrue(
        OpenApiLinter.lint(document("cardNumber", "reference")).stream()
            .anyMatch(finding -> finding.rule().equals("axiz-no-business-ids-in-query")));
  }

  private static OpenApiDocument document(String name, String location) {
    Map<String, Object> parameter =
        Map.of("name", name, "in", "query", "schema", Map.of("type", "string"));
    var operation = new LinkedHashMap<String, Object>();
    operation.put("operationId", "findRecords");
    operation.put("responses", Map.of("204", Map.of("description", "ok")));
    operation.put("security", List.of()); // acceso público explícito: el fixture es de nombres
    var item = new LinkedHashMap<String, Object>();
    item.put("get", operation);
    var parameters =
        List.of(
            location.equals("reference")
                ? Map.of("$ref", "#/components/parameters/Filter")
                : parameter);
    if (location.equals("path")) item.put("parameters", parameters);
    else operation.put("parameters", parameters);
    return OpenApiDocument.parse(
        Json.write(
            Map.of(
                "openapi",
                "3.1.0",
                "paths",
                Map.of("/v1/records", item),
                "components",
                Map.of("parameters", Map.of("Filter", parameter)))));
  }
}
