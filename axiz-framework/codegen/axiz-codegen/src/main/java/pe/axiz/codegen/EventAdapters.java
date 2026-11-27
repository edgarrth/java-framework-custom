package pe.axiz.codegen;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.render.JavaFormatter;
import pe.axiz.codegen.render.TemplateSet;

/**
 * Genera esqueletos de handlers de entrada y metadatos de Spring AOT fuera de la capa de payloads
 * propia.
 */
public final class EventAdapters {
  private EventAdapters() {}

  static String payloadPackage(Structure structure) {
    return structure == Structure.HEXAGONAL ? "domain.event" : "event";
  }

  static String handlerPackage(Structure structure) {
    return structure == Structure.HEXAGONAL ? "adapter.in.messaging" : "handler";
  }

  public static Map<String, byte[]> generate(
      String basePackage,
      String groupId,
      Structure structure,
      Stack stack,
      AsyncApiDocument contract) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    var consumed =
        contract.consumed().stream().map(AsyncApiDocument.Event::type).collect(Collectors.toSet());
    TemplateSet templates = TemplateSet.load("artifacts");
    for (var event : EventPayloads.events(groupId, contract).entrySet()) {
      Map<String, Object> context =
          Map.of(
              "payloadPackage",
              basePackage + "." + payloadPackage(structure),
              "handlerPackage",
              basePackage + "." + handlerPackage(structure),
              "configPackage",
              basePackage + ".config",
              "className",
              event.getKey(),
              "eventType",
              event.getValue().type(),
              "reactive",
              stack == Stack.REACTIVE);
      String path = "src/main/java/" + Names.packagePath(basePackage) + "/";
      files.put(
          path + "config/" + event.getKey() + "PayloadConfiguration.java",
          templates
              .renderTemplate("java/ContractPayloadConfiguration.java.mustache", context)
              .getBytes(StandardCharsets.UTF_8));
      if (consumed.contains(event.getValue().type())) {
        files.put(
            path
                + handlerPackage(structure).replace('.', '/')
                + "/"
                + event.getKey()
                + "Handler.java",
            templates
                .renderTemplate("java/ContractEventHandler.java.mustache", context)
                .getBytes(StandardCharsets.UTF_8));
      }
    }
    return JavaFormatter.formatAll(files);
  }
}
