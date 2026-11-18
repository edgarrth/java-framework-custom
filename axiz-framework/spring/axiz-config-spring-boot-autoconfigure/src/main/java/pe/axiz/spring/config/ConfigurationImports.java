package pe.axiz.spring.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/** Appends convention imports in precedence order while retaining explicit imports once. */
public final class ConfigurationImports {
  private ConfigurationImports() {}

  public static void append(ConfigurableEnvironment env, String sourceName, List<String> imports) {
    if (imports.isEmpty()) return;
    List<String> merged = new ArrayList<>();
    String existing = env.getProperty("spring.config.import");
    if (StringUtils.hasText(existing))
      merged.addAll(
          Arrays.stream(existing.split(",", -1))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .toList());
    for (String entry : imports) {
      String target = requiredLocation(entry);
      if (merged.stream().noneMatch(e -> requiredLocation(e).equals(target))) merged.add(entry);
    }
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                sourceName, Map.of("spring.config.import", String.join(",", merged))));
  }

  private static String requiredLocation(String location) {
    return location.startsWith("optional:") ? location.substring("optional:".length()) : location;
  }
}
