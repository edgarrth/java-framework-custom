package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlIdentifiersTest {
  @ParameterizedTest
  @ValueSource(strings = {"axiz_outbox", "_", "a0", "a_b_1"})
  void preservesValidIdentifiers(String value) {
    assertEquals(value, SqlIdentifiers.require(value));
  }

  @Test
  void enforcesTheExactLengthBoundary() {
    assertEquals("a".repeat(63), SqlIdentifiers.require("a".repeat(63)));
    assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.require("a".repeat(64)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "1a", "Axiz", "foo.bar", "a b", "table;drop", "a--", "á", "a\n"})
  void rejectsInvalidIdentifiers(String value) {
    assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.require(value));
  }
}
