import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;
import pe.axiz.codegen.WorkflowRelease;
import pe.axiz.codegen.render.TemplateSet;

/** Run with the built CLI classpath; never synthesizes expected output with the Python engine. */
class RefreshDeployFixture {
  @SuppressWarnings("unchecked")
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "Expected the fixture path and current deploy template source path");
    }
    Path file = Path.of(arguments[0]);
    Yaml yaml = new Yaml();
    Map<String, Object> fixture = yaml.load(Files.readString(file));
    String template = (String) fixture.get("template");
    try (var input = TemplateSet.class.getResourceAsStream("/axiz/templates/service/" + template)) {
      byte[] embedded = Objects.requireNonNull(input).readAllBytes();
      if (!Arrays.equals(embedded, Files.readAllBytes(Path.of(arguments[1])))) {
        throw new IllegalStateException(
            "Rebuild CLI: its template differs from the candidate source");
      }
      fixture.remove("templateSha256");
      fixture.put("normalization", WorkflowRelease.specification());
      fixture.put(
          "normalizedTemplateSha256",
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          WorkflowRelease.normalize(WorkflowRelease.decode(embedded))
                              .getBytes(StandardCharsets.UTF_8))));
    }
    var templates = TemplateSet.load("service");
    for (Map<String, Object> item : (List<Map<String, Object>>) fixture.get("cases")) {
      item.put(
          "rendered",
          WorkflowRelease.normalize(
              templates.renderTemplate(template, (Map<String, Object>) item.get("context"))));
    }
    Files.writeString(file, yaml.dump(fixture), StandardCharsets.UTF_8);
  }
}
