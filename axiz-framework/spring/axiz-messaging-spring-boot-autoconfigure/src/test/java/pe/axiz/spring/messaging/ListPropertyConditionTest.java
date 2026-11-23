package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import pe.axiz.spring.messaging.ConditionalOnListProperty.OnListPropertyCondition;

class ListPropertyConditionTest {

  private static final String TOPICS = "axiz.messaging.kafka.topics";

  private static List<String> topics(String... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test", map));
    return OnListPropertyCondition.values(env, TOPICS);
  }

  @Test
  void aYamlListOfTopicsEnablesTheConsumers() {
    // una lista YAML llega al entorno como claves indexadas, nunca como
    // "axiz.messaging.kafka.topics", por lo que "${axiz.messaging.kafka.topics:}" se resolvería
    // como "" y los consumidores nunca se crearían
    assertEquals(
        List.of("payments", "audit"), topics(TOPICS + "[0]", "payments", TOPICS + "[1]", "audit"));
  }

  @Test
  void aCommaSeparatedValueEnablesTheConsumers() {
    assertEquals(List.of("payments", "audit"), topics(TOPICS, "payments,audit"));
  }

  @Test
  void noTopicsOrBlankTopicsKeepThemOff() {
    assertEquals(List.of(), topics());
    assertEquals(List.of(), topics(TOPICS, ""));
    assertEquals(List.of(), topics(TOPICS + "[0]", " "));
  }
}
