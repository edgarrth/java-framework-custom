package pe.axiz.codegen.asyncapi;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;

/** Checks declared subscriptions without starting Spring or resolving environment secrets. */
public final class AsyncApiSubscriptions {
  private static final String KEY = "axiz.messaging.consumer.subscriptions";

  private AsyncApiSubscriptions() {}

  public static List<String> problems(Path directory, AsyncApiDocument document)
      throws IOException {
    Set<String> expected = new LinkedHashSet<>();
    document.consumed().forEach(event -> expected.add(event.type()));
    List<String> problems = new ArrayList<>();
    Path resources = SafePaths.target(directory, "src/main/resources");
    boolean basePresent = false;
    if (Files.isDirectory(resources)) {
      try (var files = Files.list(resources)) {
        for (Path file : files.sorted().toList()) {
          String name = SafePaths.fileName(file);
          int separator = name.lastIndexOf('.');
          if (separator < 0
              || !Set.of("yaml", "yml", "properties").contains(name.substring(separator + 1)))
            continue;
          String prefix = name.substring(0, separator);
          if (!prefix.equals("application") && !prefix.startsWith("application-")) continue;
          SafePaths.target(directory, "src/main/resources/" + name);
          List<Object> declarations = new ArrayList<>();
          if (name.endsWith(".properties")) {
            Properties values = new Properties();
            values.load(new StringReader(Files.readString(file)));
            if (values.containsKey(KEY)) declarations.add(values.getProperty(KEY));
            else {
              var indexed =
                  values.stringPropertyNames().stream()
                      .filter(key -> key.matches(Pattern.quote(KEY) + "\\[[0-9]+\\]"))
                      .sorted()
                      .map(values::getProperty)
                      .toList();
              if (!indexed.isEmpty()) declarations.add(indexed);
            }
            if (name.equals("application.properties") && !declarations.isEmpty())
              basePresent = true;
          } else {
            for (Object config : SafeYaml.create(2 * 1024 * 1024).loadAll(Files.readString(file))) {
              Object value = lookup(config, KEY);
              if (value != null) {
                declarations.add(value);
                if (name.matches("application\\.(?:yaml|yml)")
                    && lookup(config, "spring.config.activate.on-profile") == null
                    && lookup(config, "spring.config.activate.on-cloud-platform") == null
                    && lookup(config, "spring.profiles") == null) basePresent = true;
              }
            }
          }
          for (Object declaration : declarations) {
            List<String> actual =
                declaration instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of(declaration.toString().split(",", -1)).stream()
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .toList();
            if (actual.size() != new LinkedHashSet<>(actual).size()
                || !expected.equals(new LinkedHashSet<>(actual)))
              problems.add(
                  name
                      + ": "
                      + KEY
                      + " must equal AsyncAPI receive types "
                      + expected
                      + "; configured "
                      + actual);
          }
        }
      }
    }
    if (!expected.isEmpty() && !basePresent)
      problems.add("Declare " + KEY + " in the base application configuration: " + expected);
    return List.copyOf(problems);
  }

  private static @Nullable Object lookup(@Nullable Object node, String path) {
    if (!(node instanceof Map<?, ?> map)) return null;
    if (map.containsKey(path)) return map.get(path);
    int dot = path.indexOf('.');
    if (dot < 0) return map.get(path);
    // Spring accepts nested keys and dotted prefixes such as axiz.messaging.
    for (int split = dot; split >= 0; split = path.indexOf('.', split + 1)) {
      Object value = lookup(map.get(path.substring(0, split)), path.substring(split + 1));
      if (value != null) return value;
    }
    return null;
  }
}
