package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.render.YamlProperties;

class KafkaIamGenerationTest {
  @Test
  @SuppressWarnings("unchecked")
  void genericKafkaDoesNotSelectAwsAndMskSelectsAuthenticationExplicitly() {
    for (Stack stack : Stack.values()) {
      for (boolean msk : List.of(false, true)) {
        var project =
            ProjectGenerator.withDefaults()
                .generate(
                    Fixtures.request(
                        "business",
                        Structure.HEXAGONAL,
                        stack,
                        "aurora",
                        List.of(msk ? "messaging-msk" : "messaging-kafka")));
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> main =
            YamlProperties.flatten(yaml.load(project.text("src/main/resources/application.yaml")));
        Map<String, Object> local =
            YamlProperties.flatten(
                yaml.load(project.text("src/main/resources/application-local.yaml")));
        assertEquals(msk ? "msk" : null, main.get("axiz.messaging.kafka.authentication"));
        assertEquals(msk ? "configured" : null, local.get("axiz.messaging.kafka.authentication"));
        assertFalse(main.containsKey("axiz.messaging.kafka.iam"));
        assertFalse(local.containsKey("axiz.messaging.kafka.iam"));
        assertEquals(
            msk, project.text("pom.xml").contains("axiz-messaging-msk-spring-boot-starter"));
        assertTrue(project.text("compose.yaml").contains("kafka:"));
        assertEquals(
            msk,
            project.files().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("values-prod.yaml"))
                .anyMatch(
                    entry ->
                        new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8)
                            .contains("axiz.messaging.kafka.authentication")));
      }
    }
  }
}
