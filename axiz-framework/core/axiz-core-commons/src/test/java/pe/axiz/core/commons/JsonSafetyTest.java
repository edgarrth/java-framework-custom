package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.axiz.core.commons.json.JsonReader;

class JsonSafetyTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"01", "1.", "1e", ".5", "+1", "{\"a\":1,\"a\":2}", "{\"a\":null,\"a\":1}"})
  void rejectsAmbiguousOrMalformedJson(String json) {
    assertThrows(JsonReader.JsonException.class, () -> JsonReader.parse(json));
  }

  @Test
  void boundsTheNumeralInsideAnAllowedDocument() {
    var failure =
        assertThrows(JsonReader.JsonException.class, () -> JsonReader.parse("9".repeat(101)));
    assertTrue(failure.getMessage().contains("number too long"));
  }

  @Test
  @Timeout(5)
  void rejectsAnExpensiveNumeralBeforeConstructingIt() {
    assertThrows(JsonReader.JsonException.class, () -> JsonReader.parse("9".repeat(999_000)));
  }
}
