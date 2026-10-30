package pe.axiz.codegen.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Archetypes and capabilities shipped with the framework ({@code axiz/archetypes/catalog.yaml} and
 * {@code axiz/archetypes/capabilities.yaml}).
 */
public final class Catalog {

  public static final String AURORA = "aurora";
  public static final String R2DBC = "r2dbc";
  public static final String DYNAMODB = "dynamodb";

  private final Map<String, Archetype> archetypes;
  private final Map<String, Capability> capabilities;
  private final Map<Framework, Style> styles;

  private Catalog(
      Map<String, Archetype> archetypes,
      Map<String, Capability> capabilities,
      Map<Framework, Style> styles) {
    this.archetypes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(archetypes));
    this.capabilities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    this.styles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(styles));
  }

  public static Catalog load() {
    ClassLoader cl = Catalog.class.getClassLoader();
    Map<String, Archetype> archetypes = new LinkedHashMap<>();
    for (Map<String, Object> a : list(read(cl, "axiz/archetypes/catalog.yaml").get("archetypes"))) {
      Archetype archetype =
          new Archetype(
              str(a, "name"),
              str(a, "role"),
              strings(a.get("frameworks")).stream().map(Framework::of).toList(),
              strings(a.get("stacks")).stream().map(Stack::of).toList(),
              strings(a.get("persistence")),
              strings(a.get("starters")),
              str(a, "deployment"),
              Boolean.TRUE.equals(a.get("rest")),
              str(a, "kind"),
              map(a.get("properties")));
      archetypes.put(archetype.name(), archetype);
    }
    Map<String, Object> capabilityDoc = read(cl, "axiz/archetypes/capabilities.yaml");
    Map<String, Capability> capabilities = new LinkedHashMap<>();
    for (Map<String, Object> c : list(capabilityDoc.get("capabilities"))) {
      Capability cap =
          new Capability(
              str(c, "name"),
              str(c, "starter"),
              str(c, "description"),
              strings(c.get("dependencies")),
              strings(c.get("requires")),
              map(c.get("properties")),
              map(c.get("local")),
              strings(c.get("configMapKeys")),
              strings(c.get("secrets")),
              String.valueOf(c.getOrDefault("example", "")).strip(),
              stores(c.get("persistence")));
      capabilities.put(cap.name(), cap);
    }
    Map<Framework, Style> styles = new LinkedHashMap<>();
    map(capabilityDoc.get("styles"))
        .forEach(
            (id, v) -> {
              Map<String, Object> m = map(v);
              styles.put(
                  Framework.of(id),
                  new Style(strings(m.get("capabilities")), strings(m.get("jdbc-tables"))));
            });
    Catalog catalog = new Catalog(archetypes, capabilities, styles);
    catalog.check();
    return catalog;
  }

  /** Stores a capability can declare in its {@code persistence} map. */
  public static final List<String> STORES = List.of(AURORA, R2DBC, DYNAMODB);

  /**
   * The store of a service: its persistence, except Aurora from the reactive stack, which is
   * reached over R2DBC instead of JDBC/JPA.
   */
  public static String store(String persistence, Stack stack) {
    return AURORA.equals(persistence) && stack == Stack.REACTIVE ? R2DBC : persistence;
  }

  /** How a store is described to users (validation messages). */
  public static String storeLabel(String store) {
    return switch (store) {
      case R2DBC -> "aurora (reactive stack, R2DBC)";
      case AURORA -> "aurora (servlet stack, JPA)";
      default -> store;
    };
  }

  /** Every reference between entries must resolve; a broken catalog fails fast. */
  void check() {
    for (Capability c : capabilities.values()) {
      List<String> requires = new ArrayList<>(c.requires());
      c.persistence()
          .forEach(
              (store, s) -> {
                if (!STORES.contains(store)) {
                  throw new IllegalStateException(
                      "Capability " + c.name() + " declares unknown store " + store);
                }
                requires.addAll(s.requires());
              });
      for (String r : requires) {
        if (!capabilities.containsKey(r)) {
          throw new IllegalStateException("Capability " + c.name() + " requires unknown " + r);
        }
      }
      for (String d : c.dependencies()) {
        if (d.split(":", -1).length != 2) {
          throw new IllegalStateException(
              "Dependency " + d + " of " + c.name() + " is not groupId:artifactId");
        }
      }
    }
    styles.forEach(
        (framework, style) -> {
          for (String c : style.capabilities()) {
            if (!capabilities.containsKey(c)) {
              throw new IllegalStateException(
                  "Style " + framework.id() + " brings unknown capability " + c);
            }
          }
          for (String t : style.jdbcTables()) {
            if (!Style.TABLES.contains(t)) {
              throw new IllegalStateException(
                  "Style " + framework.id() + " declares unknown table " + t);
            }
          }
        });
    for (Archetype a : archetypes.values()) {
      for (String s : a.starters()) {
        if (!capabilities.containsKey(s)) {
          throw new IllegalStateException(
              "Archetype " + a.name() + " uses unknown capability " + s);
        }
      }
    }
  }

  /**
   * The given capabilities plus everything they require regardless of the store, in a stable order
   * (requirements first).
   *
   * @throws IllegalArgumentException for an unknown capability
   */
  public List<Capability> resolve(Collection<String> names) {
    return resolve(names, "none");
  }

  /**
   * The given capabilities plus everything they require on the given store (see {@link #store}), in
   * a stable order (requirements first).
   *
   * @throws IllegalArgumentException for an unknown capability
   */
  public List<Capability> resolve(Collection<String> names, String store) {
    Map<String, Capability> out = new LinkedHashMap<>();
    for (String n : names) {
      add(n, store, out, new ArrayList<>());
    }
    return List.copyOf(out.values());
  }

  private void add(String name, String store, Map<String, Capability> out, List<String> path) {
    if (out.containsKey(name)) {
      return;
    }
    Capability c = capabilities.get(name);
    if (c == null) {
      throw new IllegalArgumentException("Unknown capability " + name);
    }
    if (path.contains(name)) {
      throw new IllegalStateException("Capability cycle " + path + " -> " + name);
    }
    path.add(name);
    for (String r : c.requires(store)) {
      add(r, store, out, path);
    }
    path.remove(path.size() - 1);
    out.put(name, c);
  }

  /** What the starter of a style brings by itself ({@link Style#NONE} when nothing). */
  public Style style(Framework framework) {
    return styles.getOrDefault(framework, Style.NONE);
  }

  public Optional<Archetype> archetype(String name) {
    return Optional.ofNullable(archetypes.get(name));
  }

  public List<Archetype> archetypes() {
    return List.copyOf(archetypes.values());
  }

  public Optional<Capability> capability(String name) {
    return Optional.ofNullable(capabilities.get(name));
  }

  public List<Capability> capabilities() {
    return List.copyOf(capabilities.values());
  }

  static Map<String, Object> read(ClassLoader cl, String resource) {
    try (InputStream in = cl.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      if (!(doc instanceof Map<?, ?> m)) {
        throw new IllegalStateException(resource + " is not a YAML mapping");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) m;
      return map;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> list(@Nullable Object o) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (o instanceof List<?> l) {
      for (Object e : l) {
        if (e instanceof Map<?, ?> m) {
          out.add((Map<String, Object>) m);
        }
      }
    }
    return out;
  }

  static Map<String, Capability.Store> stores(@Nullable Object o) {
    Map<String, Capability.Store> out = new LinkedHashMap<>();
    map(o)
        .forEach(
            (k, v) -> {
              Map<String, Object> m = map(v);
              out.put(
                  k,
                  new Capability.Store(
                      map(m.get("properties")),
                      strings(m.get("dependencies")),
                      strings(m.get("requires"))));
            });
    return out;
  }

  static Map<String, Object> map(@Nullable Object o) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (o instanceof Map<?, ?> m) {
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
    }
    return out;
  }

  /** Unmodifiable copy that keeps insertion order (Map.copyOf does not). */
  static Map<String, Object> ordered(Map<String, Object> source) {
    return Snapshots.properties(source);
  }

  static List<String> strings(@Nullable Object o) {
    List<String> out = new ArrayList<>();
    if (o instanceof List<?> l) {
      l.forEach(e -> out.add(String.valueOf(e)));
    }
    return out;
  }

  static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) {
      throw new IllegalStateException("Missing '" + key + "' in catalog entry " + m.get("name"));
    }
    return String.valueOf(v);
  }
}
