package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.SafeYaml;

class ResponseExamplesTest {
  private static final OpenApiDocument CONTRACT =
      OpenApiDocument.parse(
          """
      openapi: 3.1.0
      info: {title: Response fixtures, version: 1.0.0}
      paths: {}
      components:
        schemas:
          Status: {type: string, enum: [READY, DONE]}
          Item:
            type: object
            properties:
              id: {type: string, format: uuid}
              day: {type: string, format: date}
              time: {type: string, format: date-time}
              label: {type: [string, 'null']}
              status: {$ref: '#/components/schemas/Status'}
              count: {type: integer}
              amount: {type: number}
              enabled: {type: boolean}
              children: {type: array, items: {$ref: '#/components/schemas/Item'}}
              attributes: {type: object}
              parent: {$ref: '#/components/schemas/Item'}
      """);

  @Test
  void populatedResponseExamplesHaveDeserializableTypesAndBoundedRecursion() {
    Map<?, ?> value = SafeYaml.create(10000).load(ResponseExamples.json(CONTRACT, "Item"));
    assertNotNull(UUID.fromString(value.get("id").toString()));
    assertNotNull(LocalDate.parse(value.get("day").toString()));
    assertNotNull(OffsetDateTime.parse(value.get("time").toString()));
    assertEquals("synthetic-value", value.get("label"));
    assertEquals("READY", value.get("status"));
    assertEquals(1, value.get("count"));
    assertEquals(1, value.get("amount"));
    assertEquals(true, value.get("enabled"));
    assertEquals(List.of(), value.get("children"));
    assertEquals(Map.of(), value.get("attributes"));
    assertEquals(Map.of(), ((Map<?, ?>) value.get("parent")).get("parent"));
  }

  @Test
  void collectionAndEnumResponsesKeepTheirShapeAndVoidHasNoBody() {
    List<?> values = SafeYaml.create(10000).load(ResponseExamples.json(CONTRACT, "List<Item>"));
    assertEquals(1, values.size());
    assertEquals("READY", ((Map<?, ?>) values.getFirst()).get("status"));
    assertEquals("\"READY\"", ResponseExamples.json(CONTRACT, "Status"));
    assertEquals("null", ResponseExamples.json(CONTRACT, null));
  }
}
