package pe.axiz.codegen;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.render.JavaFormatter;

/** Framework-independent payload records derived from the service's AsyncAPI contract. */
public final class EventPayloads {
  private EventPayloads() {}

  public static Map<String, byte[]> generate(
      String basePackage, String groupId, Structure structure, AsyncApiDocument contract) {
    String namespace = basePackage + "." + EventAdapters.payloadPackage(structure);
    Map<String, Object> schemas = new LinkedHashMap<>();
    events(groupId, contract)
        .forEach(
            (name, event) -> {
              validateSchema(event.payload());
              schemas.put(name, event.payload());
            });
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (var record : ApplicationModels.recordsForPackage(schemas, namespace, false)) {
      String content =
          "package "
              + namespace
              + ";\n\n/** Generated from AsyncAPI. Review .axiz-new proposals after contract sync. */\n"
              + record.get("declaration")
              + "\n";
      result.put(
          "src/main/java/" + namespace.replace('.', '/') + "/" + record.get("name") + ".java",
          content.getBytes(StandardCharsets.UTF_8));
    }
    return JavaFormatter.formatAll(result);
  }

  static Map<String, AsyncApiDocument.Event> events(String groupId, AsyncApiDocument contract) {
    Map<String, AsyncApiDocument.Event> events = new LinkedHashMap<>();
    java.util.stream.Stream.concat(contract.published().stream(), contract.consumed().stream())
        .forEach(
            event -> {
              String local =
                  event.type().startsWith(groupId + ".")
                      ? event.type().substring(groupId.length() + 1)
                      : event.type();
              String name = Names.pascal(local);
              if (!javax.lang.model.SourceVersion.isIdentifier(name)
                  || javax.lang.model.SourceVersion.isKeyword(name))
                throw new IllegalArgumentException(
                    "Event type cannot form a Java class name: " + event.type());
              var prior = events.putIfAbsent(name, event);
              if (prior != null && !prior.equals(event))
                throw new IllegalArgumentException("Event payload class collision: " + name);
            });
    return java.util.Collections.unmodifiableMap(events);
  }

  private static void validateSchema(Map<?, ?> schema) {
    if (java.util.Set.of("allOf", "oneOf", "anyOf", "not").stream().anyMatch(schema::containsKey))
      throw new IllegalArgumentException(
          "Event payload records require explicit JSON schemas; flatten compositions before generation");
    Object declared = schema.get("type");
    String kind;
    if (declared instanceof java.util.List<?> types) {
      var nonNull = types.stream().filter(type -> !"null".equals(type)).toList();
      if (nonNull.size() != 1)
        throw new IllegalArgumentException(
            "Event fields require one JSON type, optionally nullable");
      kind = String.valueOf(nonNull.getFirst());
    } else
      kind =
          declared == null && schema.containsKey("properties")
              ? "object"
              : String.valueOf(declared);
    if (!java.util.Set.of("object", "array", "string", "integer", "number", "boolean")
        .contains(kind))
      throw new IllegalArgumentException(
          "Event field requires an explicit supported JSON type: " + kind);
    if (schema.get("properties") instanceof Map<?, ?> properties) {
      for (Object property : properties.values()) {
        if (!(property instanceof Map<?, ?> value))
          throw new IllegalArgumentException("Event property schema must be a mapping");
        validateSchema(value);
      }
    }
    if (kind.equals("array")) {
      if (!(schema.get("items") instanceof Map<?, ?> items))
        throw new IllegalArgumentException("Event array requires an explicit items schema");
      validateSchema(items);
    }
  }
}
