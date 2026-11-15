package pe.axiz.codegen.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import pe.axiz.codegen.WorkflowRelease;

class DeployWorkflowFixtureTest {

  @Test
  @SuppressWarnings("unchecked")
  void sharedNormalizationCasesAreVersionIndependentAndStrict() {
    for (var item : (List<Map<String, Object>>) WorkflowRelease.document().get("cases")) {
      String value = (String) item.get("value");
      if (Boolean.TRUE.equals(item.get("error"))) {
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowRelease.normalize(value),
            (String) item.get("id"));
      } else
        assertEquals(
            item.get("expected"), WorkflowRelease.normalize(value), (String) item.get("id"));
    }
    assertThrows(
        IllegalArgumentException.class, () -> WorkflowRelease.decode(new byte[] {(byte) 0xff}));
  }

  @Test
  @SuppressWarnings("unchecked")
  void lightweightWorkflowFixturesMatchTheActualGenerator() throws Exception {
    Map<String, Object> fixture;
    try (var input = getClass().getResourceAsStream("/workflow-contracts/deploy-rendering.yaml")) {
      fixture = new Yaml().load(Objects.requireNonNull(input));
    }
    assertEquals(WorkflowRelease.specification(), fixture.get("normalization"));
    String template = (String) fixture.get("template");
    try (var input = TemplateSet.class.getResourceAsStream("/axiz/templates/service/" + template)) {
      byte[] source = Objects.requireNonNull(input).readAllBytes();
      assertEquals(
          fixture.get("normalizedTemplateSha256"),
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          WorkflowRelease.normalize(WorkflowRelease.decode(source))
                              .getBytes(StandardCharsets.UTF_8))),
          "Refresh fixtures using the built Axiz generator when this template changes");
    }
    var templates = TemplateSet.load("service");
    var cases = (List<Map<String, Object>>) fixture.get("cases");
    assertEquals(11, cases.size(), "Nine demos, module fallback, and YAML escaping");
    for (var item : cases) {
      assertEquals(
          item.get("rendered"),
          WorkflowRelease.normalize(
              templates.renderTemplate(template, (Map<String, Object>) item.get("context"))),
          (String) item.get("id"));
    }
  }
}
