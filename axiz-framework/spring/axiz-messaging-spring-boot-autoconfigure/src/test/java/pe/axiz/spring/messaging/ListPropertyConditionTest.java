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
    // a YAML list reaches the environment as indexed keys, never as "axiz.messaging.kafka.topics",
    // so "${axiz.messaging.kafka.topics:}" resolves to "" and the consumers were never created
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
