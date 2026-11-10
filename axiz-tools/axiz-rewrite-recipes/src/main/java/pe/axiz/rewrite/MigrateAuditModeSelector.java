package pe.axiz.rewrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.yaml.ChangePropertyKey;
import org.openrewrite.yaml.ChangePropertyValue;
import org.openrewrite.yaml.DeleteProperty;
import org.openrewrite.yaml.format.AutoFormatVisitor;
import org.openrewrite.yaml.tree.Yaml;

/** Migrates a complete effective selector, never two independent boolean replacements. */
public final class MigrateAuditModeSelector extends ScanningRecipe<MigrateAuditModeSelector.State> {
  private static final String MODE = "axiz.audit.mode";
  private static final String DURABLE = "axiz.audit.durable.enabled";
  private static final String ASYNC = "axiz.audit.async.enabled";
  private static final String TODO =
      "# TODO axiz-2.0: migrar audit selector a axiz.audit.mode manualmente";

  record Document(UUID id, Path group, Map<String, String> values) {}

  record Decision(boolean manual, @Nullable String newMode, Set<String> remove) {}

  static final class State {
    final Map<UUID, Document> documents = new HashMap<>();
    final Map<Path, Integer> selectors = new HashMap<>();
    final Set<Path> external = new HashSet<>();

    void add(UUID id, Path path, Map<String, String> values, boolean profiled) {
      Path group = group(path);
      boolean hasSelector =
          values.containsKey(MODE) || values.containsKey(DURABLE) || values.containsKey(ASYNC);
      if (profiled && hasSelector
          || values.keySet().stream()
              .anyMatch(
                  key ->
                      key.startsWith("spring.config.")
                          || key.equals("spring.profiles")
                          || key.startsWith("spring.profiles.")
                          || key.equals("_axiz.duplicate"))
          || values.values().stream().anyMatch(State::referencesLegacySelector))
        external.add(group);
      if (hasSelector) selectors.merge(group, 1, Integer::sum);
      if (!values.containsKey(DURABLE) && !values.containsKey(ASYNC)) return;
      documents.put(id, new Document(id, group, Map.copyOf(values)));
    }

    private static boolean referencesLegacySelector(String value) {
      return List.of(DURABLE, ASYNC).stream()
          .anyMatch(key -> value.contains("${" + key + "}") || value.contains("${" + key + ":"));
    }

    Decision decision(UUID id) {
      Document doc = documents.get(id);
      if (doc == null) return new Decision(false, null, Set.of());
      var values = doc.values();
      if (external.contains(doc.group())
          || selectors.getOrDefault(doc.group(), 0) > 1
          || values.entrySet().stream()
              .anyMatch(
                  e ->
                      Set.of(MODE, DURABLE, ASYNC).contains(e.getKey())
                          && (e.getValue().contains("${") || e.getValue().equals("?"))))
        return new Decision(true, null, Set.of());
      for (String key : List.of(DURABLE, ASYNC))
        if (values.containsKey(key)
            && !Set.of("true", "false").contains(values.get(key).toLowerCase(Locale.ROOT)))
          return new Decision(true, null, Set.of());
      boolean durable = Boolean.parseBoolean(values.getOrDefault(DURABLE, "false"));
      boolean async = Boolean.parseBoolean(values.getOrDefault(ASYNC, "true"));
      String explicit = values.getOrDefault(MODE, "").toUpperCase(Locale.ROOT);
      if (!explicit.isEmpty()) {
        if (!Set.of("DURABLE", "ASYNC", "SYNC").contains(explicit)
            || durable && !explicit.equals("DURABLE")) return new Decision(true, null, Set.of());
        Set<String> remove = new HashSet<>();
        if (values.containsKey(DURABLE) && durable == explicit.equals("DURABLE"))
          remove.add(DURABLE);
        if (values.containsKey(ASYNC)
            && !explicit.equals("DURABLE")
            && async == explicit.equals("ASYNC")) remove.add(ASYNC);
        return new Decision(false, null, Set.copyOf(remove));
      }
      return new Decision(
          false, durable ? "DURABLE" : async ? "ASYNC" : "SYNC", Set.of(DURABLE, ASYNC));
    }
  }

  @Override
  public String getDisplayName() {
    return "Migrate the axiz audit mode selector";
  }

  @Override
  public String getDescription() {
    return "Preserves durable precedence and explicit modes; marks profiles, imports, placeholders"
        + " and conflicts for manual migration.";
  }

  @Override
  public State getInitialValue(ExecutionContext ctx) {
    return new State();
  }

  @Override
  public TreeVisitor<?, ExecutionContext> getScanner(State state) {
    return new TreeVisitor<Tree, ExecutionContext>() {
      @Override
      public Tree preVisit(Tree tree, ExecutionContext ctx) {
        stopAfterPreVisit();
        if (tree instanceof Yaml.Documents yaml) {
          for (var doc : yaml.getDocuments()) {
            Map<String, String> values = new LinkedHashMap<>();
            flatten(doc.getBlock(), "", values);
            state.add(doc.getId(), yaml.getSourcePath(), values, profiled(yaml.getSourcePath()));
          }
        } else if (tree instanceof Properties.File file) {
          Map<String, String> values = new LinkedHashMap<>();
          for (var content : file.getContent())
            if (content instanceof Properties.Entry entry)
              put(values, entry.getKey(), entry.getValue().getText());
          state.add(
              file.getId(),
              file.getSourcePath(),
              values,
              profiled(file.getSourcePath()) || file.printAll().matches("(?s).*\\R#\\s*---.*"));
        }
        return tree;
      }
    };
  }

  @Override
  public TreeVisitor<?, ExecutionContext> getVisitor(State state) {
    return new TreeVisitor<Tree, ExecutionContext>() {
      @Override
      public Tree preVisit(Tree tree, ExecutionContext ctx) {
        stopAfterPreVisit();
        if (tree instanceof Yaml.Documents yaml) {
          var changed = new ArrayList<Yaml.Document>();
          boolean any = false;
          for (var doc : yaml.getDocuments()) {
            Decision decision = state.decision(doc.getId());
            var updated = doc;
            if (decision.manual()) {
              if (!doc.getPrefix().contains(TODO)) {
                String prefix = doc.getPrefix();
                if (doc != yaml.getDocuments().getFirst() && !prefix.endsWith("\n")) prefix += "\n";
                updated = doc.withPrefix(prefix + TODO + "\n");
              }
            } else if (!decision.remove().isEmpty()) {
              Yaml.Documents single = yaml.withDocuments(List.of(doc));
              String mode = decision.newMode();
              if (mode != null) {
                String selected =
                    Objects.requireNonNull(state.documents.get(doc.getId()))
                            .values()
                            .containsKey(DURABLE)
                        ? DURABLE
                        : ASYNC;
                single =
                    (Yaml.Documents)
                        new ChangePropertyKey(selected, MODE, false, null, null)
                            .getVisitor()
                            .visitNonNull(single, ctx);
                single =
                    (Yaml.Documents)
                        new ChangePropertyValue(MODE, mode, null, false, false, null)
                            .getVisitor()
                            .visitNonNull(single, ctx);
              }
              for (String key : List.of(DURABLE, ASYNC))
                if (decision.remove().contains(key))
                  single =
                      (Yaml.Documents)
                          new DeleteProperty(key, true, false, null)
                              .getVisitor()
                              .visitNonNull(single, ctx);
              single =
                  (Yaml.Documents)
                      new AutoFormatVisitor<ExecutionContext>(null).visitNonNull(single, ctx);
              updated = single.getDocuments().getFirst();
            }
            changed.add(updated);
            any |= updated != doc;
          }
          return any ? yaml.withDocuments(changed) : yaml;
        }
        if (tree instanceof Properties.File file) {
          Decision decision = state.decision(file.getId());
          if (decision.manual())
            return file.getPrefix().contains(TODO)
                ? file
                : file.withPrefix(TODO + "\n" + file.getPrefix());
          if (decision.remove().isEmpty()) return file;
          var content = new ArrayList<Properties.Content>();
          boolean inserted = false;
          for (var item : file.getContent()) {
            if (item instanceof Properties.Entry entry
                && decision.remove().contains(entry.getKey())) {
              String mode = decision.newMode();
              if (!inserted && mode != null) {
                content.add(entry.withKey(MODE).withValue(entry.getValue().withText(mode)));
                inserted = true;
              }
            } else content.add(item);
          }
          return file.withContent(content);
        }
        return tree;
      }
    };
  }

  private static void flatten(Yaml.Block block, String prefix, Map<String, String> values) {
    if (!(block instanceof Yaml.Mapping mapping)) return;
    for (var entry : mapping.getEntries()) {
      if (!(entry.getKey() instanceof Yaml.Scalar key)) continue;
      String path = prefix.isEmpty() ? key.getValue() : prefix + "." + key.getValue();
      if (entry.getValue() instanceof Yaml.Mapping nested) flatten(nested, path, values);
      else
        put(
            values,
            path,
            entry.getValue() instanceof Yaml.Scalar value ? value.getValue().strip() : "?");
    }
  }

  private static void put(Map<String, String> values, String key, String value) {
    if (values.putIfAbsent(key, value) != null) values.put("_axiz.duplicate", "true");
  }

  private static boolean profiled(Path path) {
    return path.getFileName().toString().matches("application-.+\\.(yml|yaml|properties)");
  }

  private static Path group(Path path) {
    for (int i = 0; i < path.getNameCount(); i++)
      if (path.getName(i).toString().equals("src"))
        return i == 0 ? Path.of("") : path.subpath(0, i);
    Path parent = path.getParent();
    return parent == null ? Path.of("") : parent;
  }
}
