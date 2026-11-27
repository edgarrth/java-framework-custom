package pe.axiz.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

  enum Color {
    RED
  }

  @Test
  void writesNestedStructures() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("s", "a\"b\\c\n\u0001");
    map.put("n", 1);
    map.put("d", new BigDecimal("10.50"));
    map.put("nan", Double.NaN);
    map.put("b", true);
    map.put("null", null);
    map.put("list", List.of(1, "x", Color.RED));
    map.put("at", Instant.parse("2026-01-01T00:00:00Z"));
    map.put("arr", new Object[] {1, 2});
    assertEquals(
        "{\"s\":\"a\\\"b\\\\c\\n"
            + "\\u0001\",\"n\":1,\"d\":10.50,\"nan\":null,\"b\":true,\"null\":null,"
            + "\"list\":[1,\"x\",\"RED\"],\"at\":\"2026-01-01T00:00:00Z\",\"arr\":[1,2]}",
        JsonWriter.write(map));
  }

  @Test
  void rejectsDeepNesting() {
    List<Object> root = new ArrayList<>();
    List<Object> current = root;
    for (int i = 0; i < JsonReader.MAX_DEPTH + 1; i++) {
      List<Object> next = new ArrayList<>();
      current.add(next);
      current = next;
    }
    assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(root));
  }
}
