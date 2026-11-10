package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.openapi.OpenApiDocument;

class ApplicationModelsTest {
  private static String models(String schemas) {
    return ApplicationModels.records(
            OpenApiDocument.parse(
                """
        openapi: 3.1.0
        info: {title: Models, version: 1.0.0}
        paths: {}
        components:
          schemas:
        """
                    + schemas.indent(4)),
            "sample")
        .toString();
  }

  @Test
  void preservesOwnedTypesAndNullableScalarFormats() {
    String result =
        models(
            """
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
            total: {type: integer, format: int64}
            amount: {type: number}
            enabled: {type: boolean}
            children: {type: array, items: {$ref: '#/components/schemas/Item'}}
            attributes: {type: object}
        """);
    for (String expected :
        new String[] {
          "enum Status",
          "@Nullable UUID id",
          "@Nullable LocalDate day",
          "@Nullable OffsetDateTime time",
          "String label",
          "@Nullable Status status",
          "Integer count",
          "Long total",
          "@Nullable BigDecimal amount",
          "Boolean enabled",
          "@Nullable List<sample.application.model.Item> children",
          "@Nullable Map<String, Object> attributes"
        }) {
      assertTrue(result.contains(expected), expected + ": " + result);
    }
  }

  @Test
  void unsupportedShapesFailInsteadOfGeneratingEmptyBusinessModels() {
    for (String schema :
        new String[] {
          "{oneOf: []}",
          "{allOf: []}",
          "{anyOf: []}",
          "{type: string}",
          "{type: string, enum: ['not-valid']}"
        }) {
      assertThrows(IllegalArgumentException.class, () -> models("Item: " + schema));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            models(
                """
        Item:
          type: object
          properties:
            remote: {$ref: 'https://example.invalid/schema'}
        """));
  }

  @Test
  void nullableQualifiedTypesProduceCompilableJava(@TempDir Path directory) throws Exception {
    var document =
        OpenApiDocument.parse(
            """
        openapi: 3.1.0
        info: {title: Models, version: 1.0.0}
        paths: {}
        components:
          schemas:
            Item:
              type: object
              properties:
                amount: {type: number}
                items: {type: array, items: {$ref: '#/components/schemas/Item'}}
                label: {type: string}
                metadata: {type: object}
        """);
    var model = ApplicationModels.records(document, "sample").getFirst();
    var source = directory.resolve("Item.java");
    Files.writeString(
        source,
        "package sample.application.model; import org.jspecify.annotations.Nullable; "
            + model.get("declaration"));
    String annotations =
        Path.of(Nullable.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    Assertions.assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-classpath",
                annotations,
                "-d",
                directory.toString(),
                source.toString()));
    try (var loader =
        new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> type = loader.loadClass("sample.application.model.Item");
      var constructor = type.getConstructor(BigDecimal.class, List.class, String.class, Map.class);
      var items = new ArrayList<>();
      var metadata = new HashMap<String, Object>();
      metadata.put("status", "created");
      Object item = constructor.newInstance(null, items, null, metadata);
      items.add("changed");
      metadata.clear();
      Assertions.assertEquals(List.of(), type.getMethod("items").invoke(item));
      Assertions.assertEquals(Map.of("status", "created"), type.getMethod("metadata").invoke(item));
      Object absent = constructor.newInstance(null, null, null, null);
      Assertions.assertNull(type.getMethod("items").invoke(absent));
      Assertions.assertNull(type.getMethod("metadata").invoke(absent));
    }
  }
}
