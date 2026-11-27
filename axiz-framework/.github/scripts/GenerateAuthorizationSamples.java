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

/** Genera servicios reales cuyas pruebas de autorización deben rechazar un conjunto incompleto de scopes. */
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
          ProjectRequest.builder().name("authorization-" + suffix).groupId("pe.axiz.samples").basePackage("pe.axiz.samples." + suffix).description("Generated authorization acceptance").archetype("business").structure(Structure.HEXAGONAL).stack(stack).persistence("aurora").capabilities(List.of("security")).openApi(contract).asyncApi(null).frameworkVersion(args[2]).extensions(List.of()).build();
      ProjectGenerator.withDefaults()
          .generate(request)
          .writeTo(Path.of(args[0]).resolve(suffix), false);
    }
  }
}
