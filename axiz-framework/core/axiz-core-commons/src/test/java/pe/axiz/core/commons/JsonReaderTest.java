package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.commons.json.JsonWriter;

class JsonReaderTest {

  @Test
  void parsesAllTypes() {
    Map<String, Object> m =
        JsonReader.parseObject(
            " {\"a\": 1, \"b\": [true, false, null, -2.5e3], \"c\": {\"d\": \"x\\\"\\u00e9\\n"
                + "\"}, \"e\": {}, \"f\": []} ");
    assertEquals(new BigDecimal("1"), m.get("a"));
    List<?> b = (List<?>) m.get("b");
    assertEquals(Boolean.TRUE, b.get(0));
    assertNull(b.get(2));
    assertEquals(new BigDecimal("-2.5e3"), b.get(3));
    assertEquals("x\"é\n", ((Map<?, ?>) m.get("c")).get("d"));
    assertEquals(Map.of(), m.get("e"));
    assertEquals(List.of(), m.get("f"));
  }

  @Test
  void roundTripsWithWriter() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("text", "línea\t\"x\"");
    source.put("n", new BigDecimal("10.50"));
    source.put("list", List.of("a", new BigDecimal("2")));
    assertEquals(source, JsonReader.parse(JsonWriter.write(source)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "{\"a\"}",
        "{\"a\":1,}",
        "[1 2]",
        "tru",
        "\"unterminated",
        "{a:1}",
        "01x",
        "\"\\x\"",
        "\"\\u12\"",
        "[1]]",
        "\"a\u0001\""
      })
  void rejectsMalformedInput(String json) {
    assertThrows(JsonReader.JsonException.class, () -> JsonReader.parse(json));
  }

  @Test
  void rejectsNonObjectAndDeepNesting() {
    assertThrows(JsonReader.JsonException.class, () -> JsonReader.parseObject("[]"));
    assertThrows(
        JsonReader.JsonException.class, () -> JsonReader.parse("[".repeat(100) + "]".repeat(100)));
  }
}
