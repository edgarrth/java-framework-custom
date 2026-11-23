package pe.axiz.codegen.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Arquetipos y capacidades incluidos con la estructura ({@code axiz/archetypes/catalog.yaml} y
 * {@code axiz/archetypes/capabilities.yaml}).
 */
public final class Catalog {

  public static final String AURORA = "aurora";
  public static final String R2DBC = "r2dbc";
  public static final String DYNAMODB = "dynamodb";

  private final Map<String, Archetype> archetypes;
  private final Map<String, Capability> capabilities;
  private final Map<Structure, Style> styles;

  private Catalog(
      Map<String, Archetype> archetypes,
      Map<String, Capability> capabilities,
      Map<Structure, Style> styles) {
    this.archetypes = Collections.unmodifiableMap(new LinkedHashMap<>(archetypes));
    this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    this.styles = Collections.unmodifiableMap(new LinkedHashMap<>(styles));
  }

  public static Catalog load() {
    ClassLoader cl = Catalog.class.getClassLoader();
    Map<String, Archetype> archetypes = new LinkedHashMap<>();
    for (Map<String, Object> a : list(read(cl, "axiz/archetypes/catalog.yaml").get("archetypes"))) {
      Archetype archetype =
          Archetype.builder()
              .name(str(a, "name"))
              .role(str(a, "role"))
              .structures(strings(a.get("structures")).stream().map(Structure::of).toList())
              .stacks(strings(a.get("stacks")).stream().map(Stack::of).toList())
              .persistence(strings(a.get("persistence")))
              .starters(strings(a.get("starters")))
              .deployment(str(a, "deployment"))
              .rest(Boolean.TRUE.equals(a.get("rest")))
              .kind(str(a, "kind"))
              .properties(map(a.get("properties")))
              .extensions(strings(a.get("extensions")).stream().map(Extension::of).toList())
              .build();
      archetypes.put(archetype.name(), archetype);
    }
    Map<String, Object> capabilityDoc = read(cl, "axiz/archetypes/capabilities.yaml");
    Map<String, Capability> capabilities = new LinkedHashMap<>();
    for (Map<String, Object> c : list(capabilityDoc.get("capabilities"))) {
      Capability cap =
          Capability.builder()
              .name(str(c, "name"))
              .starter(str(c, "starter"))
              .description(str(c, "description"))
              .dependencies(strings(c.get("dependencies")))
              .requires(strings(c.get("requires")))
              .requiresAnyOf(strings(c.get("requiresAnyOf")))
              .properties(map(c.get("properties")))
              .localProperties(map(c.get("local")))
              .configMapKeys(strings(c.get("configMapKeys")))
              .secrets(strings(c.get("secrets")))
              .example(String.valueOf(c.getOrDefault("example", "")).strip())
              .persistence(stores(c.get("persistence")))
              .stacks(strings(c.get("stacks")).stream().map(Stack::of).toList())
              .build();
      capabilities.put(cap.name(), cap);
    }
    Map<Structure, Style> styles = new LinkedHashMap<>();
    map(capabilityDoc.get("styles"))
        .forEach(
            (id, v) -> {
              Map<String, Object> m = map(v);
              styles.put(Structure.of(id), new Style(strings(m.get("capabilities"))));
            });
    Catalog catalog = new Catalog(archetypes, capabilities, styles);
    catalog.check();
    return catalog;
  }

  /** Almacenes que una capacidad puede declarar en su mapa {@code persistence}. */
  public static final List<String> STORES = List.of(AURORA, R2DBC, DYNAMODB, "none");

  /**
   * El almacén de un servicio: su persistencia, salvo Aurora desde el stack reactivo, a la que se
   * accede mediante R2DBC en lugar de JDBC/JPA.
   */
  public static String store(String persistence, Stack stack) {
    return AURORA.equals(persistence) && stack == Stack.REACTIVE ? R2DBC : persistence;
  }

  /** Cómo se describe un almacén a los usuarios (mensajes de validación). */
  public static String storeLabel(String store) {
    return switch (store) {
      case R2DBC -> "aurora (reactive stack, R2DBC)";
      case AURORA -> "aurora (servlet stack, JPA)";
      default -> store;
    };
  }

  /** Toda referencia entre entradas debe resolverse; un catálogo roto falla de inmediato. */
  void check() {
    for (Capability c : capabilities.values()) {
      List<String> requires = new ArrayList<>(c.requires());
      requires.addAll(c.requiresAnyOf());
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
        (structure, style) -> {
          for (String c : style.capabilities()) {
            if (!capabilities.containsKey(c)) {
              throw new IllegalStateException(
                  "Style " + structure.id() + " brings unknown capability " + c);
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
   * Las capacidades indicadas más todo lo que requieren, sin importar el almacén, en un orden
   * estable (primero los requisitos).
   *
   * @throws IllegalArgumentException ante una capacidad desconocida
   */
  public List<Capability> resolve(Collection<String> names) {
    return resolve(names, "none");
  }

  /**
   * Las capacidades indicadas más todo lo que requieren en el almacén indicado (ver {@link
   * #store}), en un orden estable (primero los requisitos).
   *
   * @throws IllegalArgumentException ante una capacidad desconocida
   */
  public List<Capability> resolve(Collection<String> names, String store) {
    Map<String, Capability> out = new LinkedHashMap<>();
    for (String n : names) {
      add(n, store, out, new ArrayList<>());
    }
    return List.copyOf(out.values());
  }

  /**
   * Composiciones incompletas o en conflicto, compartidas por new, add y doctor. No elige un
   * proveedor ni inventa una política de producción.
   */
  public List<String> missingAlternatives(Collection<Capability> resolved) {
    var names = resolved.stream().map(Capability::name).collect(Collectors.toSet());
    List<String> problems = new ArrayList<>();
    if (names.contains("tokenization-vault")) {
      if (names.contains("tokenization"))
        problems.add("Select the remote tokenization consumer or the vault server, not both");
      for (String provider : List.of("tokenization-vault-dynamodb", "tokenization-vault-redis")) {
        if (!names.contains(provider)) problems.add("tokenization-vault requires " + provider);
      }
    }
    problems.addAll(
        resolved.stream()
            .filter(
                capability ->
                    !capability.requiresAnyOf().isEmpty()
                        && capability.requiresAnyOf().stream().noneMatch(names::contains))
            .map(
                capability ->
                    capability.name()
                        + " requires one of "
                        + String.join(", ", capability.requiresAnyOf()))
            .toList());
    return List.copyOf(problems);
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

  /**
   * Lo que aporta por sí mismo el starter de un estilo ({@link Style#NONE} cuando no aporta nada).
   */
  public Style style(Structure structure) {
    return styles.getOrDefault(structure, Style.NONE);
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

  /** Copia no modificable que conserva el orden de inserción (Map.copyOf no lo hace). */
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
