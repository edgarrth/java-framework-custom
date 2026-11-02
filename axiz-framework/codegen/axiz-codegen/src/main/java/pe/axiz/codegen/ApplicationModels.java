package pe.axiz.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.OpenApiDocument;

/** Generates owned application records; transport annotations remain in the HTTP models. */
final class ApplicationModels {
  private ApplicationModels() {}

  static List<Map<String, Object>> records(OpenApiDocument contract, String basePackage) {
    Map<?, ?> schemas = mapping(mapping(contract.raw().get("components")).get("schemas"));
    return recordsForPackage(schemas, basePackage + ".application.model", true);
  }

  static List<Map<String, Object>> recordsForPackage(
      Map<?, ?> schemas, String namespace, boolean nullableAnnotations) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var entry : schemas.entrySet()) {
      String name = Names.pascal(entry.getKey().toString());
      Map<?, ?> schema = mapping(entry.getValue());
      if (schema.containsKey("allOf")
          || schema.containsKey("oneOf")
          || schema.containsKey("anyOf")) {
        throw new IllegalArgumentException(
            "Hexagonal application model requires an explicit object schema: " + name);
      }
      if (schema.get("enum") instanceof List<?> values) {
        if (values.stream()
            .anyMatch(
                v ->
                    (!javax.lang.model.SourceVersion.isIdentifier(v.toString())
                        || javax.lang.model.SourceVersion.isKeyword(v.toString())))) {
          throw new IllegalArgumentException(
              "Application enum values must be Java identifiers: " + name);
        }
        result.add(
            Map.of(
                "name",
                name,
                "declaration",
                "public enum "
                    + name
                    + " { "
                    + String.join(", ", values.stream().map(Object::toString).toList())
                    + " }"));
        continue;
      }
      if (!"object".equals(schema.get("type")) && !schema.containsKey("properties")) {
        throw new IllegalArgumentException("Application schema must be an object or enum: " + name);
      }
      List<String> fields = new ArrayList<>();
      List<String> copies = new ArrayList<>();
      for (var property : mapping(schema.get("properties")).entrySet()) {
        String propertyName = property.getKey().toString();
        if (!javax.lang.model.SourceVersion.isIdentifier(propertyName)
            || javax.lang.model.SourceVersion.isKeyword(propertyName)) {
          throw new IllegalArgumentException(
              "Application property requires an explicit mapping: " + propertyName);
        }
        Map<?, ?> propertySchema = mapping(property.getValue());
        boolean required =
            schema.get("required") instanceof List<?> names && names.contains(propertyName);
        boolean nullable =
            propertySchema.get("type") instanceof List<?> types && types.contains("null");
        String javaType = type(propertySchema, namespace);
        String copy = null;
        if (javaType.startsWith("java.util.List<")) {
          copy = "java.util.List.copyOf(" + propertyName + ")";
        } else if (javaType.startsWith("java.util.Map<")) {
          copy =
              "java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>("
                  + propertyName
                  + "))";
        }
        if (copy != null) {
          copies.add(
              propertyName
                  + " = "
                  + (!required || nullable ? propertyName + " == null ? null : " : "")
                  + copy
                  + ";");
        }
        if (nullableAnnotations && (!required || nullable)) {
          int generic = javaType.indexOf('<');
          String rawType = generic < 0 ? javaType : javaType.substring(0, generic);
          int qualifier = rawType.lastIndexOf('.') + 1;
          javaType =
              javaType.substring(0, qualifier) + "@Nullable " + javaType.substring(qualifier);
        }
        fields.add(javaType + " " + propertyName);
      }
      result.add(
          Map.of(
              "name",
              name,
              "declaration",
              "public record "
                  + name
                  + "("
                  + String.join(", ", fields)
                  + ") {"
                  + (copies.isEmpty()
                      ? ""
                      : "\n  public "
                          + name
                          + " {\n    "
                          + String.join("\n    ", copies)
                          + "\n  }\n")
                  + "}"));
    }
    return result;
  }

  private static Map<?, ?> mapping(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? map : Map.of();
  }

  private static String type(Map<?, ?> schema, String namespace) {
    Object ref = schema.get("$ref");
    if (ref instanceof String text) {
      if (!text.startsWith("#/components/schemas/")) {
        throw new IllegalArgumentException(
            "Application models require local schema references: " + text);
      }
      return namespace + "." + Names.pascal(text.substring(text.lastIndexOf('/') + 1));
    }
    Object declared = schema.get("type");
    String kind =
        declared instanceof List<?> types
            ? types.stream()
                .map(Object::toString)
                .filter(t -> !t.equals("null"))
                .findFirst()
                .orElse("object")
            : String.valueOf(declared);
    return switch (kind) {
      case "string" ->
          switch (String.valueOf(schema.get("format"))) {
            case "date" -> "java.time.LocalDate";
            case "date-time" -> "java.time.OffsetDateTime";
            case "uuid" -> "java.util.UUID";
            default -> "String";
          };
      case "integer" -> "int64".equals(schema.get("format")) ? "Long" : "Integer";
      case "number" -> "java.math.BigDecimal";
      case "boolean" -> "Boolean";
      case "array" -> "java.util.List<" + type(mapping(schema.get("items")), namespace) + ">";
      default -> "java.util.Map<String, Object>";
    };
  }
}
