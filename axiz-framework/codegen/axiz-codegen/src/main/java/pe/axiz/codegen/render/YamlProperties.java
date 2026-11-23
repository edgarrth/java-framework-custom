package pe.axiz.codegen.render;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Propiedades de Spring Boot como YAML: las claves planas ({@code a.b[0].c}) se anidan en mapas y
 * listas y se escriben en estilo de bloque, de modo que las propiedades de las capacidades pueden
 * combinarse sin claves raíz duplicadas.
 */
public final class YamlProperties {

  private static final Pattern INDEXED = Pattern.compile("^([^\\[]+)\\[(\\d+)]$");

  private YamlProperties() {}

  /** Anida las claves planas; las entradas posteriores sobrescriben a las anteriores. */
  public static Map<String, Object> nest(Map<String, ?> flat) {
    Map<String, Object> root = new LinkedHashMap<>();
    flat.forEach((key, value) -> put(root, key.split("\\.", -1), 0, value));
    return root;
  }

  /** Aplana un mapa anidado de vuelta a claves de Spring Boot ({@code a.b[0].c}). */
  public static Map<String, Object> flatten(Map<String, ?> nested) {
    Map<String, Object> out = new LinkedHashMap<>();
    flatten("", nested, out);
    return out;
  }

  /** Combina propiedades planas en un documento YAML existente y conserva sus demás entradas. */
  public static String merge(String existingYaml, Map<String, ?> flat) {
    return merge(existingYaml, flat, List.of());
  }

  /**
   * Combina propiedades planas y descarta las claves bajo los prefijos indicados (p. ej., una
   * entrada provisional que reemplaza una real).
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
    remove(current, "", removePrefixes);
    flat.forEach((key, value) -> put(current, key.split("\\.", -1), 0, value));
    return dump(current, 0);
  }

  /** YAML en estilo de bloque, con cada línea indentada con {@code indent} espacios. */
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
    // Los puntos de una clave YAML existente pueden ser literales (anotaciones de Kubernetes,
    // cabeceras o claves planas de Spring). Esa clave se conserva en lugar de aplanar y recrear el
    // documento.
    for (int end = parts.length; end > i + 1; end--) {
      String literal = String.join(".", Arrays.copyOfRange(parts, i, end));
      if (!node.containsKey(literal)) {
        Matcher indexed = INDEXED.matcher(literal);
        if (indexed.matches() && node.containsKey(indexed.group(1))) {
          String[] remaining = Arrays.copyOfRange(parts, end - 1, parts.length);
          remaining[0] = literal;
          put(node, remaining, 0, value);
          return;
        }
        continue;
      }
      if (end == parts.length) {
        node.put(literal, value == null ? "" : value);
      } else {
        Object existing = node.get(literal);
        Map<String, Object> child =
            existing instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
        node.put(literal, child);
        put(child, parts, end, value);
      }
      return;
    }
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

  private static void remove(@Nullable Object node, String prefix, List<String> prefixes) {
    if (node instanceof Map<?, ?> map) {
      var entries = map.entrySet().iterator();
      while (entries.hasNext()) {
        var entry = entries.next();
        String path =
            prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
        if (prefixes.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "."))) {
          entries.remove();
        } else {
          remove(entry.getValue(), path, prefixes);
        }
      }
    } else if (node instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) remove(list.get(i), prefix + "[" + i + "]", prefixes);
    }
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
