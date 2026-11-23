package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import pe.axiz.core.json.JsonReader;

/**
 * Cada interruptor booleano que se usa para la composición debe aparecer en los metadatos generados
 * para el IDE.
 */
class BooleanConfigurationMetadataTest {
  private static final Pattern CONDITION =
      Pattern.compile("@ConditionalOnBooleanProperty\\((.*?)\\)", Pattern.DOTALL);
  private static final Pattern PREFIX = Pattern.compile("prefix\\s*=\\s*\"([^\"]+)\"");
  private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

  @Test
  void everyBooleanConditionHasMetadataWithItsActualAbsentPropertyDefault() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("platform/axiz-parent-core")))
      root = root.getParent();
    assertNotNull(root, "Framework root required for metadata inventory");
    Map<String, Map<?, ?>> metadata = new HashMap<>();
    Map<String, Boolean> expected = new HashMap<>();
    try (var paths = Files.walk(root.resolve("spring"))) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        if (path.toString()
            .endsWith("target/classes/META-INF/spring-configuration-metadata.json")) {
          Object properties = JsonReader.parseObject(Files.readString(path)).get("properties");
          if (properties instanceof List<?> entries)
            for (Object entry : entries) {
              Map<?, ?> property = (Map<?, ?>) entry;
              metadata.put((String) property.get("name"), property);
            }
        } else if (path.toString().contains("/src/main/java/")
            && path.toString().endsWith(".java")) {
          var conditions = CONDITION.matcher(Files.readString(path));
          while (conditions.find()) {
            String arguments = conditions.group(1);
            var prefixes = PREFIX.matcher(arguments);
            String prefix = "";
            if (prefixes.find()) {
              prefix = prefixes.group(1).replaceAll("\\.$", "") + ".";
              arguments = prefixes.replaceFirst("");
            }
            boolean absent =
                Pattern.compile("matchIfMissing\\s*=\\s*true").matcher(arguments).find();
            var names = QUOTED.matcher(arguments);
            while (names.find()) {
              String key = prefix + names.group(1);
              Boolean previous = expected.putIfAbsent(key, absent);
              if (previous != null)
                assertEquals(previous, absent, "Contradictory defaults for " + key);
            }
          }
        }
      }
    }
    assertTrue(expected.size() >= 30, "Boolean switch inventory must not pass empty");
    for (var entry : expected.entrySet()) {
      var property = metadata.get(entry.getKey());
      assertNotNull(property, "Missing compiled metadata for " + entry.getKey());
      assertEquals("java.lang.Boolean", property.get("type"), entry.getKey());
      assertEquals(entry.getValue(), property.get("defaultValue"), entry.getKey());
    }
  }
}
