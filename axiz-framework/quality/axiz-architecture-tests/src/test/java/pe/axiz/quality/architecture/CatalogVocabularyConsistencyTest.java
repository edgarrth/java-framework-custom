package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.quality.archunit.Archetype;
import pe.axiz.quality.archunit.Extension;
import pe.axiz.quality.archunit.Stack;
import pe.axiz.quality.archunit.Structure;

class CatalogVocabularyConsistencyTest {
  // Platform library/starter projects intentionally have no service structure.
  private static final Map<String, String> STRUCTURE_EXCEPTIONS =
      Map.of("NONE", "libraries and starters have no service structure");

  @Test
  void theActualCatalogAndArchitectureRulesShareTheirVocabulary() {
    var archetypes = Catalog.load().archetypes();
    compare(
        archetypes.stream().filter(a -> a.kind().equals("service")).map(a -> a.name()).toList(),
        Archetype.values(),
        Map.of());
    compare(
        archetypes.stream().flatMap(a -> a.stacks().stream()).map(Enum::name).toList(),
        Stack.values(),
        Map.of());
    compare(
        archetypes.stream().flatMap(a -> a.structures().stream()).map(Enum::name).toList(),
        Structure.values(),
        STRUCTURE_EXCEPTIONS);
    compare(
        archetypes.stream().flatMap(a -> a.extensions().stream()).map(Enum::name).toList(),
        Extension.values(),
        Map.of());
  }

  private static void compare(
      Collection<String> catalog, Enum<?>[] architecture, Map<String, String> exceptions) {
    Set<String> actual =
        catalog.stream()
            .map(s -> s.replace('-', '_').toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> expected =
        Arrays.stream(architecture).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    var onlyCatalog = new TreeSet<>(actual);
    onlyCatalog.removeAll(expected);
    var onlyArchitecture = new TreeSet<>(expected);
    onlyArchitecture.removeAll(actual);
    assertEquals(exceptions.keySet(), onlyCatalog, "Unexpected or stale catalog exception");
    assertEquals(Set.of(), onlyArchitecture, "Architecture enum absent from actual catalog");
  }
}
