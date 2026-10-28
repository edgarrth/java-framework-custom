package pe.axiz.spring.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiResponsesTest {

  @TempDir Path directory;

  static final String CONTRACT =
      """
      openapi: 3.1.0
      paths:
        /items/~recent:
          parameters: []
          get:
            responses:
              '200':
                content:
                  application/json:
                    schema: {$ref: '#/components/schemas/Page'}
                    example: {items: [{status: OK, date: '2026-10-27'}], nextCursor: null}
                    examples:
                      more:
                        value: {items: [], nextCursor: next}
      components:
        schemas:
          Page:
            type: object
            required: [items, nextCursor]
            additionalProperties: false
            properties:
              items:
                type: array
                items: {$ref: '#/components/schemas/Item'}
              nextCursor: {type: [string, 'null']}
          Item:
            type: object
            required: [status, date]
            properties:
              status: {enum: [OK, FAILED]}
              date: {type: string, format: date}
      """;

  OpenApiResponses contract(String yaml) throws IOException {
    Path path = directory.resolve("openapi.yaml");
    Files.writeString(path, yaml);
    return new OpenApiResponses(path);
  }

  @Test
  void validatesActualJsonAndAllInlineExamplesWithLocalReferences() throws IOException {
    var contract = contract(CONTRACT);
    assertEquals(2, contract.assertExamples());
    contract.assertResponse(
        "/items/~recent",
        "GET",
        200,
        "application/json; charset=UTF-8",
        """
        {"items":[{"status":"OK","date":"2026-10-27"}],"nextCursor":null}
        """);
    contract.assertResponse(
        "/items/~recent",
        "get",
        200,
        "application/json",
        """
        {"items":[],"nextCursor":"more"}
        """);
  }

  @Test
  void rejectsWrongTypesMissingFieldsNestedViolationsAndInvalidFormats() throws IOException {
    var contract = contract(CONTRACT);
    for (String json :
        new String[] {
          "{\"items\":[],\"nextCursor\":123}",
          "{\"items\":[]}",
          "{\"items\":[{\"status\":\"OTHER\",\"date\":\"2026-10-27\"}],\"nextCursor\":null}",
          "{\"items\":[{\"status\":\"OK\",\"date\":\"2026-02-30\"}],\"nextCursor\":null}",
          "{\"items\":[],\"nextCursor\":null,\"extra\":true}"
        }) {
      assertThrows(
          AssertionError.class,
          () -> contract.assertResponse("/items/~recent", "get", 200, "application/json", json));
    }
  }

  @Test
  void failsWhenTheResponseContractIsMissing() throws IOException {
    var contract = contract(CONTRACT);
    assertThrows(
        AssertionError.class,
        () -> contract.assertResponse("/absent", "get", 200, "application/json", "{}"));
    assertThrows(
        AssertionError.class,
        () -> contract.assertResponse("/items/~recent", "post", 200, "application/json", "{}"));
    assertThrows(
        AssertionError.class,
        () -> contract.assertResponse("/items/~recent", "get", 201, "application/json", "{}"));
    assertThrows(
        AssertionError.class,
        () -> contract.assertResponse("/items/~recent", "get", 200, "text/plain", "{}"));
  }

  @Test
  void examplesCannotSilentlyPassWithoutValidation() throws IOException {
    assertThrows(
        AssertionError.class,
        () -> contract(CONTRACT.replace("nextCursor: null", "nextCursor: 12")).assertExamples());
    assertThrows(
        AssertionError.class,
        () ->
            contract(
                    CONTRACT.replace(
                        "value: {items: [], nextCursor: next}", "externalValue: ignored.json"))
                .assertExamples());
    assertThrows(
        AssertionError.class, () -> contract("openapi: 3.1.0\npaths: {}").assertExamples());
    assertThrows(
        IllegalArgumentException.class,
        () -> contract(CONTRACT.replace("openapi: 3.1.0", "openapi: 3.0.3")));
  }
}
