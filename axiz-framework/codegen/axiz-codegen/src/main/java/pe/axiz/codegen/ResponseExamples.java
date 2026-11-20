package pe.axiz.codegen;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.Json;
import pe.axiz.codegen.openapi.OpenApiDocument;

/** Small, typed synthetic values for generated controller mapping tests. */
final class ResponseExamples {
  private ResponseExamples() {}

  static String json(OpenApiDocument document, @Nullable String responseSchema) {
    Map<?, ?> schemas = mapping(mapping(document.raw().get("components")).get("schemas"));
    if (responseSchema == null) return "null";
    boolean list = responseSchema.startsWith("List<");
    String name = list ? responseSchema.substring(5, responseSchema.length() - 1) : responseSchema;
    Object value = sample(mapping(schemas.get(name)), schemas, new HashSet<>());
    return Json.write(list ? List.of(value) : value);
  }

  private static Object sample(Map<?, ?> schema, Map<?, ?> schemas, Set<String> visiting) {
    if (schema.get("$ref") instanceof String reference) {
      String name = reference.substring(reference.lastIndexOf('/') + 1);
      if (!visiting.add(name)) return Map.of();
      try {
        return sample(mapping(schemas.get(name)), schemas, visiting);
      } finally {
        visiting.remove(name);
      }
    }
    if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) {
      return values.getFirst();
    }
    Object declared = schema.get("type");
    String type =
        declared instanceof List<?> types
            ? types.stream()
                .map(Object::toString)
                .filter(t -> !t.equals("null"))
                .findFirst()
                .orElse("object")
            : String.valueOf(declared);
    return switch (type) {
      case "string" ->
          switch (String.valueOf(schema.get("format"))) {
            case "date" -> "2026-01-02";
            case "date-time" -> "2026-01-02T03:04:05Z";
            case "uuid" -> "00000000-0000-4000-8000-000000000001";
            default -> "synthetic-value";
          };
      case "integer", "number" -> 1;
      case "boolean" -> true;
      case "array" -> List.of();
      default -> {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var property : mapping(schema.get("properties")).entrySet()) {
          fields.put(
              property.getKey().toString(),
              sample(mapping(property.getValue()), schemas, visiting));
        }
        yield fields;
      }
    };
  }

  private static Map<?, ?> mapping(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? map : Map.of();
  }
}
