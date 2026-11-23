package pe.axiz.codegen.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Los workflows generados fijan una sola release de los workflows reutilizables de Axiz, y cada
 * referencia lleva la anotación con la que release-please la actualiza.
 */
class WorkflowTemplatesTest {

  private static final Pattern REFERENCE =
      Pattern.compile("edgarrth/java-framework-custom/\\.github/workflows/[a-z-]+\\.yaml@(\\S+)");

  @Test
  void everyWorkflowTemplatePinsTheSameAnnotatedRelease() throws IOException {
    Set<String> releases = new TreeSet<>();
    int references = 0;
    for (String set : List.of("service", "library", "starter", "artifacts")) {
      for (var entry : TemplateSet.load(set).entries()) {
        if (!entry.path().startsWith(".github/workflows/")) continue;
        for (String line : source(set, entry.template()).lines().toList()) {
          var matcher = REFERENCE.matcher(line);
          if (!matcher.find()) continue;
          references++;
          releases.add(matcher.group(1));
          assertTrue(
              line.endsWith("# x-release-please-version"),
              set + "/" + entry.template() + ": " + line);
        }
      }
    }
    assertTrue(references > 0, "los workflows generados usan los workflows reutilizables");
    assertEquals(1, releases.size(), releases::toString);
    assertTrue(
        releases.iterator().next().matches("axiz-workflows-v\\d+\\.\\d+\\.\\d+"),
        releases::toString);
  }

  @Test
  void aTemplateEntryExposesItsSourceAndDestination() {
    var entries = TemplateSet.load("library").entries();
    assertFalse(entries.isEmpty());
    assertTrue(
        entries.stream()
            .anyMatch(
                e ->
                    e.path().equals(".github/workflows/ci.yaml")
                        && e.template().endsWith("ci.yaml.mustache")));
  }

  private static String source(String set, String template) throws IOException {
    String path =
        template.startsWith("/")
            ? "axiz/templates" + template
            : "axiz/templates/" + set + "/" + template;
    try (InputStream in = TemplateSet.class.getClassLoader().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(in, path).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
