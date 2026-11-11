import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.SafeYaml;

/** Generates real services whose authorization tests must reject an incomplete set of scopes. */
class GenerateAuthorizationSamples {
  public static void main(String[] args) throws Exception {
    Map<String, Object> document =
        SafeYaml.create(2 * 1024 * 1024).load(Files.readString(Path.of(args[1])));
    document.put("security", List.of(Map.of("oauth", List.of("orders:read", "orders:write"))));
    if (!(document.get("paths") instanceof Map<?, ?> paths))
      throw new IllegalArgumentException("Authorization fixture needs paths");
    for (Object item : paths.values()) {
      if (!(item instanceof Map<?, ?> path)) continue;
      for (Object value : path.values()) {
        if (value instanceof Map<?, ?> operation && operation.containsKey("operationId"))
          operation.remove("security");
      }
    }
    String contract = new Yaml().dump(document);
    for (Stack stack : List.of(Stack.SERVLET, Stack.REACTIVE)) {
      String suffix = stack == Stack.SERVLET ? "servlet" : "reactive";
      var request =
          new ProjectRequest(
              "authorization-" + suffix,
              "pe.axiz.samples",
              "pe.axiz.samples." + suffix,
              "Generated authorization acceptance",
              "business",
              Structure.HEXAGONAL,
              stack,
              "aurora",
              List.of("security"),
              contract,
              null,
              args[2]);
      ProjectGenerator.withDefaults()
          .generate(request)
          .writeTo(Path.of(args[0]).resolve(suffix), false);
    }
  }
}
