package pe.axiz.codegen.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.ArtifactGenerator;
import pe.axiz.codegen.HttpClientGenerator;
import pe.axiz.codegen.openapi.OpenApiDocument;

class SnapshotsTest {
  @Test
  void sharedYamlAliasesAreCopiedOnlyOnceWithoutExpandingTheGraph() {
    Object shared = new ArrayList<>(List.of("leaf"));
    for (int i = 0; i < 12; i++) {
      shared = List.of(shared, shared);
    }
    Map<String, Object> snapshot = Snapshots.properties(Map.of("tree", shared));
    Object node = snapshot.get("tree");
    assertNotSame(shared, node);
    for (int i = 0; i < 12; i++) {
      List<?> children = (List<?>) node;
      assertSame(children.get(0), children.get(1));
      node = children.get(0);
    }
    assertEquals(List.of("leaf"), node);
  }

  @Test
  @SuppressWarnings("unchecked")
  void nestedYamlSnapshotsPreserveOrderAndNullWithoutSharingContainers() {
    List<Object> list = new ArrayList<>();
    list.add("first");
    list.add(null);
    Set<Object> set = new LinkedHashSet<>(Set.of("PE"));
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("list", list);
    nested.put("set", set);
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("second", nested);
    input.put("first", null);
    Map<String, Object> copy = Snapshots.properties(input);
    list.clear();
    set.clear();
    nested.clear();
    input.clear();
    assertEquals(List.of("second", "first"), new ArrayList<>(copy.keySet()));
    Map<String, Object> copiedNested = (Map<String, Object>) copy.get("second");
    assertEquals(Arrays.asList("first", null), copiedNested.get("list"));
    assertEquals(Set.of("PE"), copiedNested.get("set"));
    assertThrows(UnsupportedOperationException.class, () -> copiedNested.put("x", "y"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((List<Object>) copiedNested.get("list")).add("x"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((Set<Object>) copiedNested.get("set")).add("x"));
  }

  @Test
  void resultDefendsBinaryFilesAndMutableYamlScalarsOnInputAndOutput() {
    byte[] bytes = {1, 2};
    Date date = new Date(10);
    Map<String, byte[]> files = new LinkedHashMap<>(Map.of("file", bytes));
    Map<String, Object> properties = new LinkedHashMap<>(Map.of("binary", bytes, "date", date));
    var result =
        new HttpClientGenerator.Result(files, properties, new ArrayList<>(List.of("note")));
    bytes[0] = 9;
    date.setTime(99);
    files.clear();
    properties.clear();
    assertArrayEquals(new byte[] {1, 2}, result.files().get("file"));
    result.files().get("file")[0] = 7;
    ((byte[]) result.properties().get("binary"))[0] = 7;
    ((Date) result.properties().get("date")).setTime(77);
    assertArrayEquals(new byte[] {1, 2}, result.files().get("file"));
    assertArrayEquals(new byte[] {1, 2}, (byte[]) result.properties().get("binary"));
    assertEquals(new Date(10), result.properties().get("date"));
    assertEquals(List.of("note"), result.notes());
  }

  @Test
  void artifactResultsOwnEachFileVersion() {
    byte[] original = {4};
    var result =
        new ArtifactGenerator.Result(
            Map.of("new", original), Map.of("old", original), Map.of(), List.of(), List.of());
    original[0] = 0;
    result.created().get("new")[0] = 1;
    result.updated().get("old")[0] = 2;
    assertArrayEquals(new byte[] {4}, result.created().get("new"));
    assertArrayEquals(new byte[] {4}, result.updated().get("old"));
  }

  @Test
  void cyclicPropertiesFailWithAnExplicitLimit() {
    Map<String, Object> cycle = new LinkedHashMap<>();
    cycle.put("self", cycle);
    assertThrows(IllegalArgumentException.class, () -> Snapshots.properties(cycle));
  }

  @Test
  @SuppressWarnings("unchecked")
  void rawContractCannotBeEditedBehindTheParsedOperations() {
    var contract = OpenApiDocument.parse("openapi: 3.1.0\ninfo:\n  title: Payments\npaths: {}\n");
    Map<String, Object> info = (Map<String, Object>) contract.raw().get("info");
    assertThrows(UnsupportedOperationException.class, () -> info.put("title", "Changed"));
    assertEquals("Payments", contract.title());
  }
}
