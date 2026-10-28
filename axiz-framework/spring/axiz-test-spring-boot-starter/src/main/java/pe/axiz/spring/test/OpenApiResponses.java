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
 * Asserts serialized HTTP response bodies against OpenAPI 3.1 JSON schemas, including local schema
 * references. Used with MockMvc or WebTestClient responses; no running server is required.
 */
public final class OpenApiResponses {

  private static final Set<String> METHODS =
      Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

  private final JsonNode document;
  private final SchemaRegistry registry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  /** Loads the service's source contract, rather than a copy in test resources. */
  public OpenApiResponses(Path contract) throws IOException {
    document = YAMLMapper.builder().build().readTree(contract.toFile());
    if (!document.path("openapi").asString().startsWith("3.1.")) {
      throw new IllegalArgumentException("An OpenAPI 3.1 contract is required: " + contract);
    }
  }

  /**
   * Validates the actual status, content type and serialized body against an explicitly declared
   * response. The path is the contract template, for example {@code /items/{id}}.
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

  /** Validates every inline response example (single or named) and returns their count. */
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
    // Preserve the original root so #/components/... references resolve to the source contract.
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
