package pe.axiz.spring.test;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Comprueba los cuerpos serializados de las respuestas HTTP contra esquemas JSON de OpenAPI 3.1,
 * incluidas las referencias locales de esquemas. Se usa con respuestas de MockMvc o WebTestClient;
 * no requiere un servidor en ejecución.
 */
public final class OpenApiResponses {

  private static final Set<String> METHODS =
      Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

  private final JsonNode document;
  private final SchemaRegistry registry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  /** Carga el contrato fuente del servicio, no una copia en los recursos de prueba. */
  public OpenApiResponses(Path contract) throws IOException {
    document = YAMLMapper.builder().build().readTree(contract.toFile());
    if (!document.path("openapi").asString().startsWith("3.1.")) {
      throw new IllegalArgumentException("An OpenAPI 3.1 contract is required: " + contract);
    }
  }

  /**
   * Valida el estado, el content type y el cuerpo serializado reales contra una respuesta declarada
   * explícitamente. La ruta es la plantilla del contrato, por ejemplo {@code /items/{id}}.
   */
  public void assertResponse(
      String path, String method, int status, String contentType, String body) {
    String pointer =
        "/paths/"
            + escape(path)
            + "/"
            + method.toLowerCase(Locale.ROOT)
            + "/responses/"
            + status
            + "/content/"
            + escape(contentType.split(";", 2)[0].strip());
    validate(pointer, body);
  }

  /** Valida cada ejemplo de respuesta inline (único o con nombre) y devuelve su cantidad. */
  public int assertExamples() {
    int count = 0;
    for (var path : document.path("paths").properties()) {
      for (var operation : path.getValue().properties()) {
        if (!METHODS.contains(operation.getKey())) {
          continue;
        }
        for (var response : operation.getValue().path("responses").properties()) {
          for (var media : response.getValue().path("content").properties()) {
            String pointer =
                "/paths/"
                    + escape(path.getKey())
                    + "/"
                    + operation.getKey()
                    + "/responses/"
                    + escape(response.getKey())
                    + "/content/"
                    + escape(media.getKey());
            if (media.getValue().has("example")) {
              validate(pointer, media.getValue().path("example").toString());
              count++;
            }
            for (var example : media.getValue().path("examples").properties()) {
              if (!example.getValue().has("value")) {
                throw new AssertionError("An inline example value is required: " + pointer);
              }
              validate(pointer, example.getValue().path("value").toString());
              count++;
            }
          }
        }
      }
    }
    if (count == 0) {
      throw new AssertionError("The contract declares no inline response examples");
    }
    return count;
  }

  private void validate(String pointer, String body) {
    JsonNode schema = document.at(pointer + "/schema");
    if (!schema.isObject() && !schema.isBoolean()) {
      throw new AssertionError("Response schema is not declared at " + pointer);
    }
    // Se conserva la raíz original para que las referencias #/components/... se resuelvan contra el
    // contrato fuente.
    ObjectNode root = ((ObjectNode) document).deepCopy();
    root.put("$ref", "#" + pointer + "/schema");
    var errors =
        registry
            .getSchema(root.toString(), InputFormat.JSON)
            .validate(
                body,
                InputFormat.JSON,
                execution ->
                    execution.executionConfig(config -> config.formatAssertionsEnabled(true)));
    if (!errors.isEmpty()) {
      throw new AssertionError("Response violates " + pointer + ": " + errors);
    }
  }

  private static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }
}
