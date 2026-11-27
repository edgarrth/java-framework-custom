package pe.axiz.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
  @Test
  void decodingCanonicalNumbersPreservesTheirSignedBytes() {
    for (Object value :
        List.of(
            0.1f,
            -0.0d,
            Double.MAX_VALUE,
            Double.MIN_VALUE,
            new BigDecimal("10.50"),
            new BigDecimal("1E+64"),
            new BigDecimal("1.234567890123456789E+64"),
            BigInteger.ONE.shiftLeft(255),
            new BigDecimal("1E-64"))) {
      String json = JsonWriter.writeCanonical(value);
      assertEquals(json, JsonWriter.writeCanonical(JsonReader.parseCanonical(json)), json);
    }
    for (String number : List.of("-0", "1e3", "1E+03", "1.000E2", "1E9999"))
      assertThrows(IllegalArgumentException.class, () -> JsonReader.parseCanonical(number));
  }

  @Test
  void deterministicOrderingNumbersAndEscapesHaveAnExplicitFormat() {
    var first = new LinkedHashMap<String, Object>();
    first.put("z", List.of(new BigDecimal("1E+3"), 0.5f, -0.0d));
    first.put("a", "\n\u2028😀\ud800");
    var second = new LinkedHashMap<String, Object>();
    second.put("a", first.get("a"));
    second.put("z", first.get("z"));
    String expected = "{\"a\":\"\\n\\u2028😀\\ud800\",\"z\":[1000,0.5,-0.0]}";
    assertEquals(expected, JsonWriter.writeCanonical(first));
    assertEquals(expected, JsonWriter.writeCanonical(second));
  }

  @Test
  void countersMatchTheActualUtf8OutputIncludingUnpairedSurrogates() {
    var random = new Random(271828);
    for (int sample = 0; sample < 1000; sample++) {
      var value = new StringBuilder();
      for (int i = 0; i < 80; i++) value.append((char) random.nextInt(65536));
      String text = value.toString();
      int bytes = JsonWriter.writeCanonical(text).getBytes(StandardCharsets.UTF_8).length;
      assertEquals(bytes, JsonWriter.escapedUtf8Length(text) + 2);
      assertEquals(bytes, JsonWriter.canonicalLength(text));
    }
    for (Object scalar :
        List.of(true, 127, Long.MIN_VALUE, 0.1f, -0.0d, new BigDecimal("1E-64"), BigInteger.ONE))
      assertEquals(
          JsonWriter.writeCanonical(scalar).getBytes(StandardCharsets.UTF_8).length,
          JsonWriter.canonicalLength(scalar));
    assertEquals(4, JsonWriter.canonicalLength(null));
  }

  @Test
  void refusesMutableUnknownHugeAndNonFiniteValuesWithoutStringifying() {
    Object hostile =
        new Object() {
          @Override
          public String toString() {
            throw new AssertionError("must not stringify");
          }
        };
    for (Object value :
        List.of(
            hostile,
            new AtomicInteger(1),
            Double.NaN,
            Float.POSITIVE_INFINITY,
            new BigDecimal("1E+4096"),
            BigInteger.ONE.shiftLeft(4096),
            Map.of(1, "not a string key")))
      assertThrows(IllegalArgumentException.class, () -> JsonWriter.writeCanonical(value));
    var cycle = new java.util.ArrayList<Object>();
    cycle.add(cycle);
    assertThrows(IllegalArgumentException.class, () -> JsonWriter.writeCanonical(cycle));
  }
}
