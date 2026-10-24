package pe.axiz.codegen.render;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;
import pe.axiz.codegen.openapi.SafeYaml;

/**
 * Spring Boot properties as YAML: flat keys ({@code a.b[0].c}) are nested into maps and lists and
 * written in block style, so capability properties can be merged without duplicate root keys.
 */
public final class YamlProperties {

  private static final Pattern INDEXED = Pattern.compile("^([^\\[]+)\\[(\\d+)]$");

  private YamlProperties() {}

  /** Nests flat keys; later entries override earlier ones. */
  public static Map<String, Object> nest(Map<String, ?> flat) {
    Map<String, Object> root = new LinkedHashMap<>();
    flat.forEach((key, value) -> put(root, key.split("\\.", -1), 0, value));
    return root;
  }

  /** Flattens a nested map back to Spring Boot keys ({@code a.b[0].c}). */
  public static Map<String, Object> flatten(Map<String, ?> nested) {
    Map<String, Object> out = new LinkedHashMap<>();
    flatten("", nested, out);
    return out;
  }

  /** Merges flat properties into an existing YAML document, keeping its other entries. */
  public static String merge(String existingYaml, Map<String, ?> flat) {
    return merge(existingYaml, flat, List.of());
  }

  /**
   * Merges flat properties and drops the keys under the given prefixes (e.g. a placeholder entry
   * that a real one replaces).
   */
  public static String merge(
      String existingYaml, Map<String, ?> flat, List<String> removePrefixes) {
    Map<String, Object> current = new LinkedHashMap<>();
    if (!existingYaml.isBlank()) {
      Object doc = SafeYaml.create(1_000_000).load(new StringReader(existingYaml));
      if (doc instanceof Map<?, ?> m) {
        m.forEach((k, v) -> current.put(String.valueOf(k), v));
      }
    }
    Map<String, Object> merged = flatten(current);
    merged
        .keySet()
        .removeIf(k -> removePrefixes.stream().anyMatch(p -> k.equals(p) || k.startsWith(p + ".")));
    merged.putAll(flat);
    return dump(nest(merged), 0);
  }

  /** Block-style YAML, each line indented by {@code indent} spaces. */
  public static String dump(Map<String, ?> nested, int indent) {
    if (nested.isEmpty()) {
      return indent == 0 ? "{}\n" : " ".repeat(indent) + "{}\n";
    }
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setIndicatorIndent(0);
    options.setPrettyFlow(true);
    options.setSplitLines(false);
    String yaml = new Yaml(new Representer(options), options).dump(nested);
    if (indent == 0) {
      return yaml;
    }
    StringBuilder b = new StringBuilder();
    String pad = " ".repeat(indent);
    yaml.lines().forEach(l -> b.append(l.isEmpty() ? "" : pad).append(l).append('\n'));
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private static void put(Map<String, Object> node, String[] parts, int i, @Nullable Object value) {
    String part = parts[i];
    boolean last = i == parts.length - 1;
    Matcher m = INDEXED.matcher(part);
    if (m.matches()) {
      String name = m.group(1);
      int index = Integer.parseInt(m.group(2));
      Object existing = node.get(name);
      List<Object> list = existing instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
      node.put(name, list);
      while (list.size() <= index) {
        list.add(last ? "" : new LinkedHashMap<String, Object>());
      }
      if (last) {
        list.set(index, value == null ? "" : value);
      } else {
        Object child = list.get(index);
        if (!(child instanceof Map<?, ?>)) {
          child = new LinkedHashMap<String, Object>();
          list.set(index, child);
        }
        put((Map<String, Object>) child, parts, i + 1, value);
      }
      return;
    }
    if (last) {
      node.put(part, value == null ? "" : value);
      return;
    }
    Object child = node.get(part);
    if (!(child instanceof Map<?, ?>)) {
      child = new LinkedHashMap<String, Object>();
      node.put(part, child);
    }
    put((Map<String, Object>) child, parts, i + 1, value);
  }

  private static void flatten(String prefix, @Nullable Object value, Map<String, Object> out) {
    if (value instanceof Map<?, ?> m && !m.isEmpty()) {
      m.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
    } else if (value instanceof List<?> l
        && !l.isEmpty()
        && l.stream().anyMatch(e -> e instanceof Map<?, ?>)) {
      for (int i = 0; i < l.size(); i++) {
        flatten(prefix + "[" + i + "]", l.get(i), out);
      }
    } else if (!prefix.isEmpty()) {
      out.put(prefix, value == null ? "" : value);
    }
  }
}
