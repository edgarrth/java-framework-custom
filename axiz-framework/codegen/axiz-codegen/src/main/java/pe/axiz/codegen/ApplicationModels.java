package pe.axiz.codegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.SourceVersion;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.Json;
import pe.axiz.codegen.openapi.OpenApiDocument;

/** Generates owned application records; transport annotations remain in the HTTP models. */
final class ApplicationModels {
  private ApplicationModels() {}

  static List<Map<String, Object>> records(OpenApiDocument contract) {
    Map<?, ?> schemas = mapping(mapping(contract.raw().get("components")).get("schemas"));
    return recordsForPackage(schemas, true);
  }

  static List<Map<String, Object>> recordsForPackage(
      Map<?, ?> schemas, boolean nullableAnnotations) {
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
                    (!SourceVersion.isIdentifier(v.toString())
                        || SourceVersion.isKeyword(v.toString())))) {
          throw new IllegalArgumentException(
              "Application enum values must be Java identifiers: " + name);
        }
        result.add(
            Map.of(
                "name",
                name,
                "record",
                false,
                "imports",
                "",
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
      List<Map<String, Object>> collections = new ArrayList<>();
      Map<String, Object> requiredCollections = new LinkedHashMap<>();
      Map<String, Object> allCollections = new LinkedHashMap<>();
      Set<String> imports = new TreeSet<>();
      for (var property : mapping(schema.get("properties")).entrySet()) {
        String propertyName = property.getKey().toString();
        if (!SourceVersion.isIdentifier(propertyName) || SourceVersion.isKeyword(propertyName)) {
          throw new IllegalArgumentException(
              "Application property requires an explicit mapping: " + propertyName);
        }
        Map<?, ?> propertySchema = mapping(property.getValue());
        boolean required =
            schema.get("required") instanceof List<?> names && names.contains(propertyName);
        boolean nullable =
            propertySchema.get("type") instanceof List<?> types && types.contains("null");
        String javaType = imported(type(propertySchema), schemas, imports);
        if (nullableAnnotations)
          javaType = javaType.replace("Map<String, Object>", "Map<String, @Nullable Object>");
        String copy = collectionCopy(propertySchema, propertyName, schemas, imports, 0);
        if (copy != null) {
          Object empty =
              javaType.startsWith("java.util.List<") || javaType.startsWith("List<")
                  ? List.of()
                  : Map.of();
          allCollections.put(propertyName, empty);
          if (required && !nullable) requiredCollections.put(propertyName, empty);
          collections.add(Map.of("name", propertyName, "nullable", !required || nullable));
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
              "record",
              true,
              "collections",
              collections,
              "requiredCollectionsJson",
              Json.write(requiredCollections).replace("\"", "\\\""),
              "allCollectionsJson",
              Json.write(allCollections).replace("\"", "\\\""),
              "imports",
              String.join("\n", imports.stream().map(value -> "import " + value + ";").toList()),
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

  private static @Nullable String collectionCopy(
      Map<?, ?> schema, String expression, Map<?, ?> schemas, Set<String> imports, int depth) {
    String type = type(schema);
    if (type.startsWith("java.util.Map<"))
      return imported("pe.axiz.core.lang.ValueSnapshots.map(" + expression + ")", schemas, imports);
    if (type.startsWith("java.util.List<")) {
      String element = "element" + depth;
      String nested =
          collectionCopy(mapping(schema.get("items")), element, schemas, imports, depth + 1);
      if (nested != null)
        return expression
            + ".stream().map("
            + element
            + " -> "
            + element
            + " == null ? null : "
            + nested
            + ").toList()";
      return imported(
          "java.util.Collections.unmodifiableList(new java.util.ArrayList<>(" + expression + "))",
          schemas,
          imports);
    }
    return null;
  }

  private static String imported(String value, Map<?, ?> schemas, Set<String> imports) {
    String result = value;
    for (String qualified :
        List.of(
            "java.math.BigDecimal",
            "java.time.LocalDate",
            "java.time.OffsetDateTime",
            "java.util.UUID",
            "java.util.List",
            "java.util.Map",
            "java.util.Collections",
            "java.util.LinkedHashMap",
            "java.util.ArrayList",
            "pe.axiz.core.lang.ValueSnapshots")) {
      String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
      if (result.contains(qualified)
          && schemas.keySet().stream()
              .noneMatch(key -> Names.pascal(key.toString()).equals(simple))) {
        imports.add(qualified);
        result = result.replace(qualified, simple);
      }
    }
    return result;
  }

  private static Map<?, ?> mapping(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? map : Map.of();
  }

  private static String type(Map<?, ?> schema) {
    Object ref = schema.get("$ref");
    if (ref instanceof String text) {
      if (!text.startsWith("#/components/schemas/")) {
        throw new IllegalArgumentException(
            "Application models require local schema references: " + text);
      }
      return Names.pascal(text.substring(text.lastIndexOf('/') + 1));
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
      case "array" -> "java.util.List<" + type(mapping(schema.get("items"))) + ">";
      default -> "java.util.Map<String, Object>";
    };
  }
}
